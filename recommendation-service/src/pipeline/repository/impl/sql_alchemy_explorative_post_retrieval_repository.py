
from sqlalchemy import text
from uuid import UUID

from pipeline.repository.explorative_post_retrieval_repository import ExplorativePostRetrievalRepository
from shared.config.database import SQLAlchemySessionProvider


class SqlAlchemyExplorativePostRetrievalRepository(ExplorativePostRetrievalRepository):
    def __init__(self, session_provider: SQLAlchemySessionProvider):
        self.session_provider = session_provider       

    def get_popular_posts(
        self,
        user_id: UUID,
        limit: int,
    ) -> list[tuple[UUID, float]]:
        with self.session_provider.session() as session:
            result = session.execute(
                text("""
                    SELECT p.post_id, p.decayed_engagement_score
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
                    ORDER BY p.decayed_engagement_score DESC
                    LIMIT :limit
                """),
                {"user_id": user_id, "limit": limit}
            )
            return [(row[0], row[1]) for row in result]


    def get_random_posts(
        self,
        user_id: UUID,
        limit: int,
    ) -> list[tuple[UUID, float]]:
        with self.session_provider.session() as session:
            result = session.execute(
                text("""
                    SELECT p.post_id, p.decayed_engagement_score
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
                {"user_id": user_id, "limit": limit}
            )
            return [(row[0], row[1]) for row in result]

    def get_unseen_tags_posts(
        self,
        user_id: UUID,
        limit: int,
    ) -> list[tuple[UUID, float]]:
        with self.session_provider.session() as session:
            result = session.execute(
                text("""
                    WITH seen_tags AS (
                        SELECT tag_name 
                        FROM post_tag_features 
                        WHERE user_id = :user_id
                    )
                    SELECT p.post_id, p.decayed_engagement_score
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
                    ORDER BY p.decayed_engagement_score DESC
                    LIMIT :limit
                """),
                {"user_id": user_id, "limit": limit}
            )
            return [(row[0], row[1]) for row in result]