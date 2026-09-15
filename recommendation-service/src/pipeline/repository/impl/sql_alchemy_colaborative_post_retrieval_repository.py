
from sqlalchemy import UUID, select, text

from pipeline.entity.user.user_features_entity import UserFeaturesRecord
from pipeline.repository.colaborative_post_retrieval_repository import CollaborativePostRetrievalRepository
from shared.config.database import SQLAlchemySessionProvider


class SqlAlchemyCollaborativePostRetrievalRepository(CollaborativePostRetrievalRepository):
    def __init__(self, session_provider: SQLAlchemySessionProvider):
            self.session_provider = session_provider

    def has_semantic_signal(self, user_id: UUID) -> bool:
        with self.session_provider.session() as session:
            statement = (
                select(UserFeaturesRecord.has_semantic_signal)
                .where(UserFeaturesRecord.user_id == user_id)
                .limit(1)
            )
            has_semantic_signal = session.scalars(statement).first()
            if has_semantic_signal is None:
                return False
            
            return has_semantic_signal

    def get_similar_users(self, user_id: UUID, limit: int) -> list[tuple[UUID, float]]:
        with self.session_provider.session() as session:
            result = session.execute(
                  text(
                       """  SELECT u.user_id, -(u.semantic_embedding <#> (
                                    SELECT semantic_embedding
                                    FROM user_features
                                    WHERE user_id = :user_id
                                )) as similarity
                            FROM user_features u
                            WHERE u.user_id != :user_id
                                AND u.has_semantic_signal = true
                                AND NOT EXISTS (
                                    SELECT 1
                                    FROM blocks
                                    WHERE (blocker_id = :user_id AND blocked_id = u.user_id)
                                        OR (blocker_id = u.user_id AND blocked_id = :user_id)
                                )
                            ORDER BY similarity DESC
                            LIMIT :limit
                       """
                  ),
                  {"user_id": user_id, "limit": limit}
            )

            return [(row[0], row[1]) for row in result]

    def get_posts_ordered_by_user_affinity(
        self,
        similar_users: list[tuple[UUID, float]],
        user_id: UUID,
        creators_per_user_limit: int,
        posts_per_creator_limit: int,
    ) -> list[UUID]:
        with self.session_provider.session() as session:
            user_ids = [str(u) for u, _ in similar_users]
            similarities = [s for _, s in similar_users]

            result = session.execute(
                text("""
                    WITH similar_users(similar_user_id, similarity) AS (
                        SELECT
                            unnest(CAST(:user_ids AS uuid[])) AS similar_user_id,
                            unnest(CAST(:similarities AS float[])) AS similarity
                    ),
                    top_creators AS (
                        SELECT
                            ucf.user_id       AS similar_user_id,
                            ucf.creator_id,
                            su.similarity * ucf.affinity_score AS combined_score,
                            ROW_NUMBER() OVER (
                                PARTITION BY ucf.user_id
                                ORDER BY ucf.affinity_score DESC
                            ) AS creator_rank
                        FROM user_creator_features ucf
                        JOIN similar_users su ON su.similar_user_id = ucf.user_id
                        WHERE ucf.creator_id != :user_id
                    ),
                    top_posts AS (
                        SELECT
                            p.post_id,
                            tc.combined_score,
                            ROW_NUMBER() OVER (
                                PARTITION BY tc.similar_user_id, tc.creator_id
                                ORDER BY p.created_at DESC
                            ) AS post_rank
                        FROM top_creators tc
                        JOIN post_features p ON p.creator_id = tc.creator_id
                        LEFT JOIN user_post_interactions upi
                            ON upi.post_id = p.post_id
                            AND upi.user_id = :user_id
                        WHERE tc.creator_rank <= :creators_per_user_limit
                            AND (upi.ever_seen IS NULL OR upi.ever_seen = false)
                            AND NOT EXISTS (
                                SELECT 1
                                FROM blocks
                                WHERE (blocker_id = :user_id AND blocked_id = p.creator_id)
                                    OR (blocker_id = p.creator_id AND blocked_id = :user_id)
                            )
                    )
                    SELECT post_id, combined_score
                    FROM top_posts
                    WHERE post_rank <= :posts_per_creator_limit
                    ORDER BY combined_score DESC
                """),
                {
                    "user_ids": user_ids,
                    "similarities": similarities,
                    "user_id": user_id,
                    "creators_per_user_limit": creators_per_user_limit,
                    "posts_per_creator_limit": posts_per_creator_limit,
                }
            )

            return [(row[0], row[1]) for row in result]
