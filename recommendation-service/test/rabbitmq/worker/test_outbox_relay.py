import threading
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
from uuid import uuid4

import pytest
from aiormq.abc import DeliveredMessage
from sqlalchemy import text

from rabbitmq.event.feed.feed_events import TrendingPostSentEvent
from rabbitmq.worker.outbox_relay import OutboxRelayWorker
from shared.config.database import SQLAlchemyTransactionManager
from shared.outbox.outbox_publisher import OutboxPublisher


pytestmark = pytest.mark.integration


class FakeBroker:
    """Records publishes; outcomes maps a message id to an exception to raise
    or to "returned" (acked but unroutable)."""

    def __init__(self, outcomes=None, available=True):
        self.outcomes = outcomes or {}
        self.available = available
        self.published = []

    async def ping(self, timeout):
        return self.available

    async def publish(self, message, **kwargs):
        outcome = self.outcomes.get(kwargs["message_id"])
        if isinstance(outcome, Exception):
            raise outcome
        self.published.append((message, kwargs))
        if outcome == "returned":
            return DeliveredMessage(delivery=None, header=None, body=b"", channel=None)
        return SimpleNamespace(name="Basic.Ack")


def add_event(db_session_factory, created_at=None):
    event = TrendingPostSentEvent(correlationId=uuid4(), posts=[uuid4()])
    with SQLAlchemyTransactionManager(db_session_factory).transaction() as session:
        OutboxPublisher().add(
            session, event, event_type="TrendingPostSent", exchange="x.feed.events", routing_key="rk.post.trending.sent"
        )
    if created_at is not None:
        execute(db_session_factory, "UPDATE outbox_events SET created_at = :created_at WHERE id = :id",
                created_at=created_at, id=event.id)
    return event


def execute(db_session_factory, sql, **params):
    with db_session_factory() as session:
        session.execute(text(sql), params)
        session.commit()


def fetch(db_session_factory, event_id):
    with db_session_factory() as session:
        return session.execute(text("SELECT * FROM outbox_events WHERE id = :id"), {"id": event_id}).mappings().one()


async def test_relay_publishes_oldest_first_and_marks_processed_after_ack(session_provider, db_session_factory):
    # Arrange
    now = datetime.now(timezone.utc)
    newer = add_event(db_session_factory, created_at=now)
    older = add_event(db_session_factory, created_at=now - timedelta(minutes=1))
    broker = FakeBroker()

    # Act
    await OutboxRelayWorker(session_provider, broker).relay_batch()

    # Assert
    assert [kwargs["message_id"] for _, kwargs in broker.published] == [str(older.id), str(newer.id)]
    message, kwargs = broker.published[0]
    assert message["correlationId"] == str(older.correlationId)
    assert kwargs["exchange"] == "x.feed.events"
    assert kwargs["routing_key"] == "rk.post.trending.sent"
    assert kwargs["persist"] is True and kwargs["mandatory"] is True
    for event in (older, newer):
        row = fetch(db_session_factory, event.id)
        assert row["status"] == "PROCESSED"
        assert row["attempts"] == 1
        assert row["processed_at"] is not None and row["locked_at"] is None


async def test_relay_stops_the_batch_at_the_first_failure_and_releases_it_as_pending(
    session_provider, db_session_factory
):
    # Arrange
    now = datetime.now(timezone.utc)
    failing = add_event(db_session_factory, created_at=now - timedelta(minutes=1))
    next_event = add_event(db_session_factory, created_at=now)
    broker = FakeBroker({str(failing.id): ConnectionError("broker down")})

    # Act
    await OutboxRelayWorker(session_provider, broker).relay_batch()

    # Assert
    assert broker.published == []
    failed_row = fetch(db_session_factory, failing.id)
    assert failed_row["status"] == "PENDING"
    assert failed_row["attempts"] == 1
    assert failed_row["last_error"] == "broker down"
    assert failed_row["locked_at"] is None
    assert fetch(db_session_factory, next_event.id)["attempts"] == 0


async def test_relay_moves_event_to_failed_once_attempts_are_exhausted(session_provider, db_session_factory):
    # Arrange
    event = add_event(db_session_factory)
    execute(db_session_factory, "UPDATE outbox_events SET attempts = 9 WHERE id = :id", id=event.id)
    broker = FakeBroker({str(event.id): RuntimeError("nack")})

    # Act
    await OutboxRelayWorker(session_provider, broker, max_attempts=10).relay_batch()

    # Assert
    row = fetch(db_session_factory, event.id)
    assert row["status"] == "FAILED"
    assert row["attempts"] == 10


async def test_relay_marks_unroutable_event_processed(session_provider, db_session_factory):
    # Arrange
    event = add_event(db_session_factory)
    broker = FakeBroker({str(event.id): "returned"})

    # Act
    await OutboxRelayWorker(session_provider, broker).relay_batch()

    # Assert
    assert fetch(db_session_factory, event.id)["status"] == "PROCESSED"


async def test_relay_reclaims_only_events_whose_lock_expired(session_provider, db_session_factory):
    # Arrange
    stale = add_event(db_session_factory)
    fresh = add_event(db_session_factory)
    execute(
        db_session_factory,
        "UPDATE outbox_events SET status = 'PROCESSING', attempts = 1, locked_at = now() - interval '2 minutes' "
        "WHERE id = :id",
        id=stale.id,
    )
    execute(
        db_session_factory,
        "UPDATE outbox_events SET status = 'PROCESSING', attempts = 1, locked_at = now() WHERE id = :id",
        id=fresh.id,
    )
    broker = FakeBroker()

    # Act
    await OutboxRelayWorker(session_provider, broker, lock_timeout_s=60).relay_batch()

    # Assert
    assert [kwargs["message_id"] for _, kwargs in broker.published] == [str(stale.id)]
    assert fetch(db_session_factory, stale.id)["attempts"] == 2
    assert fetch(db_session_factory, fresh.id)["status"] == "PROCESSING"


async def test_relay_claims_nothing_while_the_broker_is_unreachable(session_provider, db_session_factory):
    # Arrange
    event = add_event(db_session_factory)

    # Act
    await OutboxRelayWorker(session_provider, FakeBroker(available=False)).relay_batch()

    # Assert
    row = fetch(db_session_factory, event.id)
    assert row["status"] == "PENDING"
    assert row["attempts"] == 0


def test_concurrent_claims_return_each_event_exactly_once(session_provider, db_session_factory):
    # Arrange
    events = [add_event(db_session_factory) for _ in range(60)]
    worker = OutboxRelayWorker(session_provider, FakeBroker())
    claimed = []
    lock = threading.Lock()

    def claim_until_empty():
        while (row := worker._claim_next()) is not None:
            with lock:
                claimed.append(row.id)

    # Act
    threads = [threading.Thread(target=claim_until_empty) for _ in range(4)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    # Assert
    assert sorted(claimed) == sorted(event.id for event in events)


async def test_delete_processed_removes_only_old_processed_events(session_provider, db_session_factory):
    # Arrange
    old_processed = add_event(db_session_factory)
    recent_processed = add_event(db_session_factory)
    pending = add_event(db_session_factory)
    execute(
        db_session_factory,
        "UPDATE outbox_events SET status = 'PROCESSED', processed_at = now() - interval '8 days' WHERE id = :id",
        id=old_processed.id,
    )
    execute(
        db_session_factory,
        "UPDATE outbox_events SET status = 'PROCESSED', processed_at = now() WHERE id = :id",
        id=recent_processed.id,
    )

    # Act
    deleted = await OutboxRelayWorker(session_provider, FakeBroker()).delete_processed()

    # Assert
    assert deleted == 1
    with db_session_factory() as session:
        remaining = set(session.execute(text("SELECT id FROM outbox_events")).scalars())
    assert remaining == {recent_processed.id, pending.id}


def test_outbox_insert_is_rolled_back_with_its_transaction(db_session_factory):
    # Arrange
    event = TrendingPostSentEvent(correlationId=uuid4(), posts=[])

    # Act
    with pytest.raises(RuntimeError):
        with SQLAlchemyTransactionManager(db_session_factory).transaction() as session:
            OutboxPublisher().add(
                session, event, event_type="TrendingPostSent", exchange="x.feed.events",
                routing_key="rk.post.trending.sent",
            )
            raise RuntimeError("failure after the state change, before commit")

    # Assert
    with db_session_factory() as session:
        assert session.execute(text("SELECT count(*) FROM outbox_events")).scalar_one() == 0
