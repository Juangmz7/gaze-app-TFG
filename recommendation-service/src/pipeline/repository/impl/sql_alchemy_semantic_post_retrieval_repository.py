
from sqlalchemy import text
from uuid import UUID

from pipeline.repository.semantic_post_retrieval_repository import SemanticPostRetrievalRepository
from shared.config.database import SQLAlchemySessionProvider


class SqlAlchemySemanticPostRetrievalRepository(SemanticPostRetrievalRepository):
    def __init__(self, session_provider: SQLAlchemySessionProvider):
        self.session_provider = session_provider

    def get_similar_posts(self, user_id: UUID, limit: int) -> list[UUID]:
        with self.session_provider.session() as session:
            result = session.execute(
                text("""
                    SELECT p.post_id
                    FROM post_features p
                    LEFT JOIN user_post_interactions upi
                        ON upi.post_id = p.post_id
                        AND upi.user_id = :user_id
                    WHERE p.creator_id != :user_id
                        AND (upi.ever_seen IS NULL OR upi.ever_seen = false)
                        AND NOT EXISTS (
                            SELECT 1
                            FROM blocks
                            WHERE (blocker_id = :user_id AND blocked_id = p.creator_id)
                                OR (blocker_id = p.creator_id AND blocked_id = :user_id)
                        )
                    ORDER BY p.semantic_embedding <#> (
                        SELECT semantic_embedding
                        FROM user_features
                        WHERE user_id = :user_id
                    )
                    LIMIT :limit
                """),
                {"user_id": user_id, "limit": limit}
            )

            return list(result.scalars())