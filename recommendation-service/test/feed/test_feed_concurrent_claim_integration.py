"""Two replicas answering the same feed request at once: real listener, usecases and
repositories on real PostgreSQL, one transaction each. Only the pipeline is stubbed.
The first replica holds its transaction open right after claiming the event, so the
other one really reaches the claim while the first has not finished."""
import asyncio
import threading
from types import SimpleNamespace
from unittest.mock import Mock
from uuid import UUID, uuid4

import pytest
from sqlalchemy import text

from _support.feed_service import feed_request_body
from pipeline.usecase.recommended_feed_use_case import RecommendedFeedUseCase
from pipeline.worker.pipeline_worker_pool import PipelineWorkerPool
from post.usecase.exhaust_post_feed_usecase import ExhaustPostFeedUsecase
from rabbitmq.handler.post.post_feed_exhausted_event_handler import PostFeedExhaustedEventHandler
from rabbitmq.listener.feed_event_listener import FeedEventListener
from shared.config.database import SQLAlchemyTransactionManager
from shared.outbox.outbox_publisher import OutboxPublisher
from shared.repository.impl.sql_alchemy_processed_events_repository import SqlAlchemyProcessedEventsRepository

pytestmark = pytest.mark.integration

TIMEOUT_S = 30
MESSAGE = SimpleNamespace(raw_message=SimpleNamespace(routing_key="rk.post.feed.exhausted"))


class OutboxPublisherHeldAfterTheClaim(OutboxPublisher):
    """add() runs right after the claim, in its transaction: holding it here keeps
    the claimed row uncommitted. Fails afterwards when told to, as if the outbox
    insert had failed, which rolls the claim back."""

    def __init__(self, outbox_event_repository, *, fail: bool):
        super().__init__(outbox_event_repository)
        self.fail = fail
        self.claimed = threading.Event()
        self.release = threading.Event()

    def add(self, *args, **kwargs):
        self.claimed.set()
        assert self.release.wait(TIMEOUT_S), "The test never released the first replica"
        if self.fail:
            raise RuntimeError("Outbox insert failed")
        super().add(*args, **kwargs)


@pytest.fixture()
async def build_replica(db_session_factory, session_provider):
    pools = []

    def build(outbox_publisher, posts):
        pool = PipelineWorkerPool(1)
        pools.append(pool)
        orchestrator = Mock()
        orchestrator.recommend.return_value = posts
        usecase = ExhaustPostFeedUsecase(
            RecommendedFeedUseCase(orchestrator, outbox_publisher, SQLAlchemyTransactionManager(db_session_factory)),
            SqlAlchemyProcessedEventsRepository(session_provider),
        )
        return SimpleNamespace(
            listener=FeedEventListener(PostFeedExhaustedEventHandler(usecase, pool)),
            orchestrator=orchestrator,
        )

    yield build
    for pool in pools:
        await pool.shutdown()


def claim_is_waiting_for_a_lock(db_session_factory) -> bool:
    with db_session_factory() as session:
        return session.execute(text("""
            SELECT count(*) FROM pg_stat_activity
            WHERE datname = current_database()
                AND wait_event_type = 'Lock'
                AND query ILIKE 'INSERT INTO processed_events%'
        """)).scalar_one() == 1


async def race(first, second, held_publisher, db_session_factory, body):
    """Both are past the idempotency check and in their claim, the second one waiting
    for the first to finish its transaction. Returns both tasks, still running."""
    first_task = asyncio.create_task(first.listener.handle_event(body, MESSAGE))
    assert await asyncio.to_thread(held_publisher.claimed.wait, TIMEOUT_S)

    second_task = asyncio.create_task(second.listener.handle_event(body, MESSAGE))
    async with asyncio.timeout(TIMEOUT_S):
        while not await asyncio.to_thread(claim_is_waiting_for_a_lock, db_session_factory):
            await asyncio.sleep(0.02)

    assert not second_task.done()
    return first_task, second_task


def outbox_posts(db_session_factory) -> list[list[str]]:
    with db_session_factory() as session:
        rows = session.execute(text("SELECT payload FROM outbox_events")).scalars().all()
    return [row["posts"] for row in rows]


def processed_event_count(db_session_factory, event_id: UUID) -> int:
    with db_session_factory() as session:
        return session.execute(
            text("SELECT count(*) FROM processed_events WHERE event_id = :id"), {"id": event_id}
        ).scalar_one()


async def test_only_the_replica_that_commits_its_claim_publishes(
    build_replica, outbox_event_repository, db_session_factory
):
    # Arrange
    held_publisher = OutboxPublisherHeldAfterTheClaim(outbox_event_repository, fail=False)
    winner_posts, loser_posts = [uuid4()], [uuid4()]
    winner = build_replica(held_publisher, winner_posts)
    loser = build_replica(OutboxPublisher(outbox_event_repository), loser_posts)
    event_id = uuid4()
    body = feed_request_body(event_id=event_id)

    try:
        # Act
        winner_task, loser_task = await race(winner, loser, held_publisher, db_session_factory, body)
        held_publisher.release.set()

        # Assert: both finish normally, so both messages are acknowledged
        async with asyncio.timeout(TIMEOUT_S):
            await winner_task
            await loser_task
    finally:
        held_publisher.release.set()

    # Assert: both ran the pipeline, as the design accepts, but only one publishes
    winner.orchestrator.recommend.assert_called_once()
    loser.orchestrator.recommend.assert_called_once()
    assert outbox_posts(db_session_factory) == [[str(post_id) for post_id in winner_posts]]
    assert processed_event_count(db_session_factory, event_id) == 1


async def test_the_other_replica_publishes_when_the_winner_rolls_back_after_claiming(
    build_replica, outbox_event_repository, db_session_factory
):
    # Arrange
    held_publisher = OutboxPublisherHeldAfterTheClaim(outbox_event_repository, fail=True)
    failing = build_replica(held_publisher, [uuid4()])
    other_posts = [uuid4()]
    other = build_replica(OutboxPublisher(outbox_event_repository), other_posts)
    event_id = uuid4()
    body = feed_request_body(event_id=event_id)

    try:
        # Act
        failing_task, other_task = await race(failing, other, held_publisher, db_session_factory, body)
        held_publisher.release.set()

        # Assert: the failure reaches the caller, which is the retry middleware in production
        async with asyncio.timeout(TIMEOUT_S):
            with pytest.raises(RuntimeError, match="Outbox insert failed"):
                await failing_task
            await other_task
    finally:
        held_publisher.release.set()

    # Assert: the claim of the failed replica was rolled back, so the other one won
    assert outbox_posts(db_session_factory) == [[str(post_id) for post_id in other_posts]]
    assert processed_event_count(db_session_factory, event_id) == 1

    # Act: the next attempt of the failed replica
    held_publisher.claimed.clear()
    await failing.listener.handle_event(body, MESSAGE)

    # Assert: it finds the event processed and acknowledges, without running anything
    failing.orchestrator.recommend.assert_called_once()
    assert not held_publisher.claimed.is_set()
    assert outbox_posts(db_session_factory) == [[str(post_id) for post_id in other_posts]]
    assert processed_event_count(db_session_factory, event_id) == 1
