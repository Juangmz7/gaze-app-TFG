from datetime import datetime, timedelta, timezone
from unittest.mock import MagicMock
from uuid import UUID, uuid4

import pytest
from sqlalchemy import select

from pipeline.entity.post.post_features_entity import PostFeaturesRecord
from pipeline.entity.post.post_interaction_features_entity import PostInteractionFeaturesRecord
from pipeline.repository.impl.sql_alchemy_explorative_post_retrieval_repository import (
    SqlAlchemyExplorativePostRetrievalRepository,
)
from shared.config.database import SQLAlchemySessionProvider
from shared.helpers import decay


pytestmark = pytest.mark.integration
NOW = datetime(2026, 3, 1, 12, 0, 0, tzinfo=timezone.utc)


def seed_post(
    session_provider: SQLAlchemySessionProvider,
    stored_score: float,
    last_updated_at: datetime,
    created_at: datetime = NOW,
) -> UUID:
    post_id = uuid4()
    with session_provider.session() as session:
        session.add(PostFeaturesRecord(
            post_id=post_id,
            creator_id=uuid4(),
            tags=["tag-" + post_id.hex],
            tagged_users_ids=[],
            semantic_embedding=[0.1] * 1024,
            created_at=created_at,
        ))
        session.add(PostInteractionFeaturesRecord(
            post_id=post_id,
            decayed_engagement_score=stored_score,
            last_updated_at=last_updated_at,
        ))
        session.commit()
    return post_id


def all_queries(repo: SqlAlchemyExplorativePostRetrievalRepository, user_id: UUID) -> dict[str, dict[UUID, float]]:
    return {
        "popular": dict(repo.get_popular_posts(user_id, 50)),
        "unseen_tags": dict(repo.get_unseen_tags_posts(user_id, 50)),
        "random": dict(repo.get_random_posts(user_id, 50)),
        "cold_start": dict(repo.get_cold_start_posts(user_id, 50, set())),
    }


@pytest.fixture()
def session_provider(db_session_factory) -> SQLAlchemySessionProvider:
    return SQLAlchemySessionProvider(db_session_factory)


@pytest.fixture()
def repo(session_provider) -> SqlAlchemyExplorativePostRetrievalRepository:
    return SqlAlchemyExplorativePostRetrievalRepository(session_provider, clock=lambda: NOW)


def test_popular_posts_orders_recent_lower_stored_score_before_old_higher_one(session_provider, repo):
    # Arrange
    old_high = seed_post(session_provider, 10.0, NOW - timedelta(hours=1))
    recent_low = seed_post(session_provider, 5.0, NOW)

    # Act
    popular = repo.get_popular_posts(uuid4(), 10)

    # Assert
    assert [post_id for post_id, _ in popular] == [recent_low, old_high]


def test_unseen_tags_posts_orders_by_the_effective_score(session_provider, repo):
    # Arrange
    old_high = seed_post(session_provider, 10.0, NOW - timedelta(hours=1))
    recent_low = seed_post(session_provider, 5.0, NOW)

    # Act
    unseen_tags = repo.get_unseen_tags_posts(uuid4(), 10)

    # Assert
    assert [post_id for post_id, _ in unseen_tags] == [recent_low, old_high]


def test_popular_posts_returns_stored_score_times_python_decay(session_provider, repo):
    # Arrange
    seeded = {
        seed_post(session_provider, stored, NOW - elapsed): (stored, NOW - elapsed)
        for stored, elapsed in [
            (10.0, timedelta(seconds=1)),
            (20.0, timedelta(seconds=2.5)),
            (40.0, timedelta(seconds=5, milliseconds=250)),
            (3.0, timedelta(0)),
        ]
    }

    # Act
    popular = dict(repo.get_popular_posts(uuid4(), 10))

    # Assert
    assert popular.keys() == seeded.keys()
    for post_id, (stored, last_updated_at) in seeded.items():
        assert popular[post_id] == pytest.approx(stored * decay(last_updated_at, NOW), rel=1e-9)


def test_every_explorative_query_returns_the_same_effective_score(session_provider, repo):
    # Arrange
    last_updated_at = NOW - timedelta(seconds=2)
    post_id = seed_post(session_provider, 8.0, last_updated_at)
    expected = 8.0 * decay(last_updated_at, NOW)

    # Act
    results = all_queries(repo, uuid4())

    # Assert
    for name, scores in results.items():
        assert scores[post_id] == pytest.approx(expected, rel=1e-9), name


def test_post_last_updated_30_days_ago_reads_as_zero_without_underflow(session_provider, repo):
    # Arrange
    old_post = seed_post(session_provider, 1_000_000.0, NOW - timedelta(days=30))
    old_negative_post = seed_post(session_provider, -1_000_000.0, NOW - timedelta(days=30))

    # Act
    results = all_queries(repo, uuid4())

    # Assert
    for name, scores in results.items():
        assert scores[old_post] == pytest.approx(0.0, abs=1e-12), name
        assert scores[old_negative_post] == pytest.approx(0.0, abs=1e-12), name


def test_future_last_updated_at_returns_the_stored_score_unchanged(session_provider, repo):
    # Arrange
    post_id = seed_post(session_provider, 7.5, NOW + timedelta(seconds=5))

    # Act
    results = all_queries(repo, uuid4())

    # Assert
    for name, scores in results.items():
        assert scores[post_id] == pytest.approx(7.5, rel=1e-12), name


def test_negative_stored_score_decays_towards_zero_and_keeps_its_sign(session_provider, repo):
    # Arrange
    last_updated_at = NOW - timedelta(seconds=1)
    post_id = seed_post(session_provider, -4.0, last_updated_at)

    # Act
    popular = dict(repo.get_popular_posts(uuid4(), 10))

    # Assert
    assert -4.0 < popular[post_id] < 0.0
    assert popular[post_id] == pytest.approx(-4.0 * decay(last_updated_at, NOW), rel=1e-9)


def test_retrieval_queries_leave_post_interaction_features_unchanged(session_provider, repo):
    # Arrange
    seed_post(session_provider, 10.0, NOW - timedelta(seconds=3))
    seed_post(session_provider, -2.0, NOW - timedelta(days=30))
    seed_post(session_provider, 4.0, NOW + timedelta(seconds=1))

    def snapshot():
        with session_provider.session() as session:
            rows = session.execute(
                select(
                    PostInteractionFeaturesRecord.post_id,
                    PostInteractionFeaturesRecord.decayed_engagement_score,
                    PostInteractionFeaturesRecord.last_updated_at,
                )
            ).all()
        return sorted((tuple(row) for row in rows), key=lambda row: row[0])

    before = snapshot()

    # Act
    all_queries(repo, uuid4())

    # Assert
    assert snapshot() == before


def test_score_falls_as_the_clock_moves_forward(session_provider):
    # Arrange
    post_id = seed_post(session_provider, 10.0, NOW)
    now_holder = {"now": NOW}
    repo = SqlAlchemyExplorativePostRetrievalRepository(session_provider, clock=lambda: now_holder["now"])

    # Act
    first = dict(repo.get_popular_posts(uuid4(), 10))[post_id]
    now_holder["now"] = NOW + timedelta(seconds=2)
    later = dict(repo.get_popular_posts(uuid4(), 10))[post_id]

    # Assert
    assert first == pytest.approx(10.0)
    assert later == pytest.approx(10.0 * decay(NOW, NOW + timedelta(seconds=2)), rel=1e-9)


def test_naive_clock_is_rejected():
    # Arrange
    repo = SqlAlchemyExplorativePostRetrievalRepository(MagicMock(), clock=lambda: datetime(2026, 3, 1, 12, 0, 0))

    # Act / Assert
    with pytest.raises(ValueError, match="timezone-aware"):
        repo.get_popular_posts(uuid4(), 10)
