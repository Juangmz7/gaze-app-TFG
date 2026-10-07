from datetime import datetime
from uuid import uuid4

import pytest
from sqlalchemy import text

from pipeline.usecase.recommended_feed_use_case import RecommendedFeedUseCase
from pipeline.usecase.trending_feed_use_case import TrendingFeedUseCase
from shared.config.database import SQLAlchemyTransactionManager
from shared.outbox.outbox_publisher import OutboxPublisher


pytestmark = pytest.mark.integration


class StubOrchestrator:
    def __init__(self, posts=None, error=None):
        self.posts = posts or []
        self.error = error

    def recommend(self, user_id):
        if self.error:
            raise self.error
        return self.posts


def outbox_rows(db_session_factory):
    with db_session_factory() as session:
        return session.execute(text("SELECT * FROM outbox_events")).mappings().all()


def test_recommended_feed_queues_recommended_post_sent_with_the_propagated_correlation_id(db_session_factory):
    # Arrange
    user_id, correlation_id = uuid4(), uuid4()
    posts = [uuid4(), uuid4(), uuid4()]
    use_case = RecommendedFeedUseCase(
        StubOrchestrator(posts=posts), OutboxPublisher(), SQLAlchemyTransactionManager(db_session_factory)
    )

    # Act
    use_case.execute(user_id, correlation_id)

    # Assert
    [row] = outbox_rows(db_session_factory)
    assert row["event_type"] == "RecommendedPostSent"
    assert row["exchange"] == "x.feed.events"
    assert row["routing_key"] == "rk.post.feed.recommended.sent"
    assert row["status"] == "PENDING"
    assert row["correlation_id"] == correlation_id
    payload = row["payload"]
    assert payload["id"] == str(row["id"])
    assert payload["correlationId"] == str(correlation_id)
    assert payload["userId"] == str(user_id)
    assert payload["posts"] == [str(post) for post in posts]
    assert datetime.fromisoformat(payload["occurredAt"]).tzinfo is not None


def test_recommended_feed_queues_nothing_when_the_pipeline_fails(db_session_factory):
    # Arrange
    use_case = RecommendedFeedUseCase(
        StubOrchestrator(error=RuntimeError("model unavailable")),
        OutboxPublisher(),
        SQLAlchemyTransactionManager(db_session_factory),
    )

    # Act
    with pytest.raises(RuntimeError):
        use_case.execute(uuid4(), uuid4())

    # Assert
    assert outbox_rows(db_session_factory) == []


def test_trending_feed_queues_trending_post_sent(db_session_factory):
    # Arrange
    posts = [uuid4()]
    use_case = TrendingFeedUseCase(OutboxPublisher(), SQLAlchemyTransactionManager(db_session_factory))

    # Act
    use_case.publish_trending_post_sent(posts)

    # Assert
    [row] = outbox_rows(db_session_factory)
    assert row["event_type"] == "TrendingPostSent"
    assert row["routing_key"] == "rk.post.trending.sent"
    assert row["payload"]["posts"] == [str(posts[0])]
