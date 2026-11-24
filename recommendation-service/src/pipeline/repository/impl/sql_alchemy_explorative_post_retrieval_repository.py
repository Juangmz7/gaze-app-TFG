
from collections.abc import Callable, Collection
from datetime import datetime, timezone
from typing import Any

from sqlalchemy import DateTime, Float, Uuid, bindparam, text
from sqlalchemy.dialects.postgresql import ARRAY
from uuid import UUID

from pipeline.config.constants import ENGAGEMENT_DECAY_RATE_PER_SECOND
from pipeline.repository.explorative_post_retrieval_repository import ExplorativePostRetrievalRepository
from shared.config.database import SQLAlchemySessionProvider


# exp() on double precision underflows (and raises) below about -708, so the exponent is
# clamped: a post idle for that long reads as ~0 instead of failing the whole query.
MIN_DECAY_EXPONENT = -700.0

# Stored score decayed from its last update up to :now, the same as shared.helpers.decay.
# A last_updated_at in the future (clock skew) floors the elapsed time at 0 (factor 1).
EFFECTIVE_ENGAGEMENT_SCORE_SQL = f"""
    (p.decayed_engagement_score * EXP(GREATEST(
        {MIN_DECAY_EXPONENT},
        -:decay_rate * GREATEST(
            0.0,
            EXTRACT(EPOCH FROM (:now - p.last_updated_at))::double precision
        )
    )))
"""


def _utc_now() -> datetime:
    return datetime.now(timezone.utc)


class SqlAlchemyExplorativePostRetrievalRepository(ExplorativePostRetrievalRepository):
    """Read-only: the engagement score is decayed in the query and never written back."""

    def __init__(
        self,
        session_provider: SQLAlchemySessionProvider,
        clock: Callable[[], datetime] = _utc_now,
    ):
        if session_provider is None:
            raise ValueError("session_provider is required")
        if clock is None:
            raise ValueError("clock is required")
        self.session_provider = session_provider
        self.clock = clock

    def _decay_params(self) -> dict[str, Any]:
        now = self.clock()
        if not isinstance(now, datetime) or now.tzinfo is None or now.utcoffset() is None:
            raise ValueError("clock must return a timezone-aware datetime")
        return {
            "now": now.astimezone(timezone.utc),
            "decay_rate": ENGAGEMENT_DECAY_RATE_PER_SECOND,
        }

    @staticmethod
    def _query(sql: str):
        return text(sql).bindparams(
            bindparam("now", type_=DateTime(timezone=True)),
            bindparam("decay_rate", type_=Float()),
        )

    def get_popular_posts(
        self,
        user_id: UUID,
        limit: int,
    ) -> list[tuple[UUID, float]]:
        with self.session_provider.session() as session:
            result = session.execute(
                self._query(f"""
                    SELECT p.post_id, {EFFECTIVE_ENGAGEMENT_SCORE_SQL} AS effective_score
                    FROM post_interaction_features p
                    JOIN post_features pf ON p.post_id = pf.post_id
                    LEFT JOIN user_post_interactions upi
                        ON upi.post_id = p.post_id AND upi.user_id = :user_id
                    WHERE pf.creator_id != :user_id
                        AND (upi.ever_seen IS NULL OR upi.ever_seen = FALSE)
                        AND NOT EXISTS (
                            SELECT 1 FROM blocks b
                            WHERE (b.blocker_id = :user_id AND b.blocked_id = pf.creator_id)
                               OR (b.blocker_id = pf.creator_id AND b.blocked_id = :user_id)
                        )
                        AND NOT EXISTS (
                            SELECT 1 FROM follows f
                            WHERE f.follower_id = :user_id AND f.followed_id = pf.creator_id
                        )
                    ORDER BY effective_score DESC
                    LIMIT :limit
                """),
                {"user_id": user_id, "limit": limit, **self._decay_params()}
            )
            return [(row[0], row[1]) for row in result]


    def get_random_posts(
        self,
        user_id: UUID,
        limit: int,
    ) -> list[tuple[UUID, float]]:
        with self.session_provider.session() as session:
            result = session.execute(
                self._query(f"""
                    SELECT p.post_id, {EFFECTIVE_ENGAGEMENT_SCORE_SQL} AS effective_score
                    FROM post_interaction_features p
                    JOIN post_features pf ON p.post_id = pf.post_id
                    LEFT JOIN user_post_interactions upi
                        ON upi.post_id = p.post_id AND upi.user_id = :user_id
                    WHERE pf.creator_id != :user_id
                        AND (upi.ever_seen IS NULL OR upi.ever_seen = FALSE)
                        AND NOT EXISTS (
                            SELECT 1 FROM blocks b
                            WHERE (b.blocker_id = :user_id AND b.blocked_id = pf.creator_id)
                               OR (b.blocker_id = pf.creator_id AND b.blocked_id = :user_id)
                        )
                        AND NOT EXISTS (
                            SELECT 1 FROM follows f
                            WHERE f.follower_id = :user_id AND f.followed_id = pf.creator_id
                        )
                    ORDER BY RANDOM()
                    LIMIT :limit
                """),
                {"user_id": user_id, "limit": limit, **self._decay_params()}
            )
            return [(row[0], row[1]) for row in result]

    def get_unseen_tags_posts(
        self,
        user_id: UUID,
        limit: int,
    ) -> list[tuple[UUID, float]]:
        with self.session_provider.session() as session:
            result = session.execute(
                self._query(f"""
                    WITH seen_tags AS (
                        SELECT tag_name
                        FROM post_tag_features
                        WHERE user_id = :user_id
                    )
                    SELECT p.post_id, {EFFECTIVE_ENGAGEMENT_SCORE_SQL} AS effective_score
                    FROM post_interaction_features p
                    JOIN post_features pf ON p.post_id = pf.post_id
                    LEFT JOIN user_post_interactions upi
                        ON upi.post_id = p.post_id AND upi.user_id = :user_id
                    WHERE pf.creator_id != :user_id
                        AND (upi.ever_seen IS NULL OR upi.ever_seen = FALSE)
                        AND NOT EXISTS (
                            SELECT 1 FROM blocks b
                            WHERE (b.blocker_id = :user_id AND b.blocked_id = pf.creator_id)
                               OR (b.blocker_id = pf.creator_id AND b.blocked_id = :user_id)
                        )
                        AND NOT EXISTS (
                            SELECT 1 FROM follows f
                            WHERE f.follower_id = :user_id AND f.followed_id = pf.creator_id
                        )
                        AND NOT EXISTS (
                            SELECT 1
                            FROM json_array_elements_text(pf.tags) AS post_tag
                            JOIN seen_tags st ON st.tag_name = post_tag
                        )
                    ORDER BY effective_score DESC
                    LIMIT :limit
                """),
                {"user_id": user_id, "limit": limit, **self._decay_params()}
            )
            return [(row[0], row[1]) for row in result]
    def get_cold_start_posts(
        self,
        user_id: UUID,
        limit: int,
        excluded_post_ids: Collection[UUID],
    ) -> list[tuple[UUID, float]]:
        with self.session_provider.session() as session:
            result = session.execute(
                self._query(f"""
                    SELECT p.post_id, {EFFECTIVE_ENGAGEMENT_SCORE_SQL} AS effective_score
                    FROM post_interaction_features p
                    JOIN post_features pf ON p.post_id = pf.post_id
                    LEFT JOIN user_post_interactions upi
                        ON upi.post_id = p.post_id AND upi.user_id = :user_id
                    WHERE pf.creator_id != :user_id
                        AND (upi.ever_seen IS NULL OR upi.ever_seen = FALSE)
                        AND p.raw_views < 50
                        AND p.post_id <> ALL(:excluded_post_ids)
                        AND NOT EXISTS (
                            SELECT 1 FROM blocks b
                            WHERE (b.blocker_id = :user_id AND b.blocked_id = pf.creator_id)
                               OR (b.blocker_id = pf.creator_id AND b.blocked_id = :user_id)
                        )
                    ORDER BY pf.created_at DESC, p.raw_views ASC
                    LIMIT :limit
                """).bindparams(bindparam("excluded_post_ids", type_=ARRAY(Uuid(as_uuid=True)))),
                {
                    "user_id": user_id,
                    "limit": limit,
                    "excluded_post_ids": list(excluded_post_ids),
                    **self._decay_params(),
                }
            )
            return [(row[0], row[1]) for row in result]
