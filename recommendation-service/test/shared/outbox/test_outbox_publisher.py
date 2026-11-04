from uuid import uuid4

import pytest

from rabbitmq.event.feed.feed_events import TrendingPostSentEvent
from shared.outbox.committed_outbox_events import add_committed_listener, remove_committed_listener
from shared.outbox.outbox_publisher import OutboxPublisher
from shared.repository.outbox_event_repository import OutboxEventRepository
from shared.transaction.transaction_manager import after_commit_scope


pytestmark = pytest.mark.unit


class InMemoryOutboxEventRepository(OutboxEventRepository):
    def __init__(self):
        self.added = []

    def add(self, event):
        self.added.append(event)

    def claim_next(self, locked_before):
        raise NotImplementedError

    def claim_if_pending(self, event_id):
        raise NotImplementedError

    def mark_processed(self, event_id):
        raise NotImplementedError

    def mark_failed_attempt(self, event_id, attempts, status, last_error):
        raise NotImplementedError

    def delete_processed_before(self, processed_before):
        raise NotImplementedError


def test_add_stores_the_event_with_its_destination_and_camel_case_payload():
    # Arrange
    repository = InMemoryOutboxEventRepository()
    event = TrendingPostSentEvent(correlationId=uuid4(), posts=[uuid4()])

    # Act
    OutboxPublisher(repository).add(
        event, event_type="TrendingPostSent", exchange="x.feed.events", routing_key="rk.post.trending.sent"
    )

    # Assert
    [stored] = repository.added
    assert stored.id == event.id
    assert stored.correlation_id == event.correlationId
    assert stored.event_type == "TrendingPostSent"
    assert stored.exchange == "x.feed.events"
    assert stored.routing_key == "rk.post.trending.sent"
    assert stored.attempts == 0
    assert stored.payload == event.model_dump(mode="json")
    assert set(stored.payload) == {"id", "correlationId", "occurredAt", "posts"}


@pytest.fixture
def committed_ids():
    ids = []
    add_committed_listener(ids.append)
    yield ids
    remove_committed_listener(ids.append)


def add_event(repository):
    event = TrendingPostSentEvent(correlationId=uuid4(), posts=[uuid4()])
    OutboxPublisher(repository).add(
        event, event_type="TrendingPostSent", exchange="x.feed.events", routing_key="rk.post.trending.sent"
    )
    return event


def test_add_notifies_the_event_only_once_its_transaction_commits(committed_ids):
    # Arrange
    repository = InMemoryOutboxEventRepository()

    # Act
    with after_commit_scope():
        event = add_event(repository)
        notified_before_commit = list(committed_ids)

    # Assert
    assert notified_before_commit == []
    assert committed_ids == [event.id]


def test_add_does_not_notify_when_its_transaction_rolls_back(committed_ids):
    # Act
    with pytest.raises(RuntimeError):
        with after_commit_scope():
            add_event(InMemoryOutboxEventRepository())
            raise RuntimeError("rollback")

    # Assert
    assert committed_ids == []


def test_add_outside_a_transaction_notifies_right_away(committed_ids):
    # Act
    event = add_event(InMemoryOutboxEventRepository())

    # Assert
    assert committed_ids == [event.id]
