"""Repeated views of the same post by the same user (replayCount) against a real
database, and through the real service and broker for a view past the cap."""
import asyncio
import json
from uuid import UUID, uuid4

import pytest
from sqlalchemy import text

from _support.builders import T0, make_post_features, make_user_features
from _support.feed_service import POST_DLQ, POST_QUEUE, Amqp, run_service
from pipeline.config.constants import MAX_COUNTED_VIEW_REPLAYS, POST_VIEW_WEIGHT
from pipeline.repository.impl.sql_alchemy_post_features_repository import SqlAlchemyPostFeaturesRepository
from pipeline.repository.impl.sql_alchemy_post_interaction_features_repository import (
    SqlAlchemyPostInteractionFeaturesRepository,
)
from pipeline.repository.impl.sql_alchemy_post_tag_features_repository import SqlAlchemyPostTagFeaturesRepository
from pipeline.repository.impl.sql_alchemy_user_creator_features_repository import (
    SqlAlchemyUserCreatorFeaturesRepository,
)
from pipeline.repository.impl.sql_alchemy_user_features_repository import SqlAlchemyUserFeaturesRepository
from post.command.post_commands import RegisterPostViewCommand
from post.usecase.register_post_view_usecase import RegisterPostViewUsecase
from rabbitmq.event.post.post_events import InteractionSource, PostViewExitReason
from shared.config.database import SQLAlchemySessionProvider, SQLAlchemyTransactionManager

pytestmark = pytest.mark.integration

FEATURE_TABLES = ("post_interaction_features", "user_creator_features", "post_tag_features", "user_features")


def _seed_post_and_user(session_provider) -> tuple[UUID, UUID]:
    post = make_post_features(tags=["python", "ml"])
    user_id = uuid4()
    SqlAlchemyPostFeaturesRepository(session_provider).save(post)
    SqlAlchemyPostInteractionFeaturesRepository(session_provider).create_empty(post.post_id, T0)
    SqlAlchemyUserFeaturesRepository(session_provider).save(make_user_features(user_id=user_id))
    return post.post_id, user_id


def _view_command(post_id: UUID, user_id: UUID, replay_count: int) -> RegisterPostViewCommand:
    return RegisterPostViewCommand(
        event_id=uuid4(),
        correlation_id=uuid4(),
        occurred_at=T0,
        view_id=uuid4(),
        post_id=post_id,
        user_id=user_id,
        source=InteractionSource.HOME_FEED,
        feed_position=1,
        duration_ms=10000,
        time_watched_ms=6000,
        completion_percent=60,
        exit_reason=PostViewExitReason.NAVIGATED_AWAY,
        server_timestamp=T0,
        replay_count=replay_count,
    )


def _snapshot(db_session_factory) -> dict[str, list[dict]]:
    with db_session_factory() as session:
        return {
            table: [dict(row) for row in session.execute(text(f"SELECT * FROM {table} ORDER BY 1, 2")).mappings()]
            for table in FEATURE_TABLES
        }


def test_views_with_replay_count_1_to_10_leave_the_engagement_score_and_raw_views_bounded(db_session_factory):
    # Arrange: every view at T0, the post's last update, so nothing decays
    session_provider = SQLAlchemySessionProvider(db_session_factory)
    usecase = RegisterPostViewUsecase(
        SqlAlchemyUserCreatorFeaturesRepository(session_provider),
        SqlAlchemyPostTagFeaturesRepository(session_provider),
        SqlAlchemyPostFeaturesRepository(session_provider),
        SqlAlchemyUserFeaturesRepository(session_provider),
        SqlAlchemyPostInteractionFeaturesRepository(session_provider),
        SQLAlchemyTransactionManager(db_session_factory),
    )
    post_id, user_id = _seed_post_and_user(session_provider)

    # Act
    for replay_count in range(1, 11):
        usecase.execute(_view_command(post_id, user_id, replay_count))

    # Assert
    harmonic = sum(1.0 / n for n in range(1, MAX_COUNTED_VIEW_REPLAYS + 1))
    with db_session_factory() as session:
        post_row = session.execute(
            text("SELECT decayed_engagement_score, raw_views, raw_impressions, decayed_impressions "
                 "FROM post_interaction_features WHERE post_id = :id"),
            {"id": post_id},
        ).mappings().one()
        creator_views = session.execute(
            text("SELECT raw_views FROM user_creator_features WHERE user_id = :id"), {"id": user_id}
        ).scalar_one()
        tag_views = session.execute(
            text("SELECT raw_views FROM post_tag_features WHERE user_id = :id"), {"id": user_id}
        ).scalars().all()
    assert post_row["decayed_engagement_score"] == pytest.approx(POST_VIEW_WEIGHT * harmonic)
    assert post_row["decayed_impressions"] == pytest.approx(harmonic)
    assert post_row["raw_views"] == MAX_COUNTED_VIEW_REPLAYS
    assert post_row["raw_impressions"] == MAX_COUNTED_VIEW_REPLAYS
    assert creator_views == MAX_COUNTED_VIEW_REPLAYS
    assert tag_views == [MAX_COUNTED_VIEW_REPLAYS, MAX_COUNTED_VIEW_REPLAYS]


@pytest.fixture(scope="module")
def service(postgres_url, rabbitmq_url):
    with run_service(postgres_url, rabbitmq_url) as running:
        yield running


@pytest.fixture()
async def amqp(service, rabbitmq_url):
    client = await Amqp.connect(rabbitmq_url)
    await client.purge(POST_QUEUE, POST_DLQ)
    yield client
    await client.close()


async def _wait_until(condition, timeout=30.0):
    async with asyncio.timeout(timeout):
        while not await asyncio.to_thread(condition):
            await asyncio.sleep(0.05)


async def test_a_post_viewed_event_above_the_cap_is_acked_marked_processed_and_changes_no_feature_row(
    amqp, db_session_factory
):
    # Arrange: rows a counted view would change, then their state before the event
    session_provider = SQLAlchemySessionProvider(db_session_factory)
    post_id, user_id = _seed_post_and_user(session_provider)
    usecase = RegisterPostViewUsecase(
        SqlAlchemyUserCreatorFeaturesRepository(session_provider),
        SqlAlchemyPostTagFeaturesRepository(session_provider),
        SqlAlchemyPostFeaturesRepository(session_provider),
        SqlAlchemyUserFeaturesRepository(session_provider),
        SqlAlchemyPostInteractionFeaturesRepository(session_provider),
        SQLAlchemyTransactionManager(db_session_factory),
    )
    usecase.execute(_view_command(post_id, user_id, replay_count=1))
    before = _snapshot(db_session_factory)
    event_id, correlation_id = uuid4(), uuid4()
    # Literal JSON as post-command-service writes it
    body = json.dumps({
        "id": str(event_id),
        "correlationId": str(correlation_id),
        "occurredAt": "2026-11-04T10:00:00Z",
        "viewId": str(uuid4()),
        "postId": str(post_id),
        "userId": str(user_id),
        "source": "HOME_FEED",
        "feedPosition": 1,
        "durationMs": 10000,
        "timeWatchedMs": 6000,
        "completionPercent": 60,
        "exitReason": "NAVIGATED_AWAY",
        "serverTimestamp": "2026-11-04T10:00:00Z",
        "replayCount": MAX_COUNTED_VIEW_REPLAYS + 1,
    }).encode()

    # Act
    await amqp.publish(body, exchange="x.post.events", routing_key="rk.post.viewed")

    # Assert
    def event_was_processed():
        with db_session_factory() as session:
            return session.execute(
                text("SELECT event_name FROM processed_events WHERE event_id = :id AND correlation_id = :cid"),
                {"id": event_id, "cid": correlation_id},
            ).scalar_one_or_none() == "PostViewedEvent"

    await _wait_until(event_was_processed)
    assert _snapshot(db_session_factory) == before
    assert await amqp.drain(POST_DLQ) == []
    assert await amqp.drain(POST_QUEUE) == []
