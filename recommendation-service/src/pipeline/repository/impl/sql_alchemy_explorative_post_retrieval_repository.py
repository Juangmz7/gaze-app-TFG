
from email.mime import text
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
            session.execute(
                text("""
                    SELECT 
                    FROM 
                    WHERE 
                    ORDER BY
                """
                ),
                {"user_id": user_id, "limit": limit}
            )


    def get_random_posts(
        self,
        user_id: UUID,
        limit: int,
    ) -> list[tuple[UUID, float]]:
        with self.session_provider.session() as session:
            session.execute(
                text("""
                    SELECT 
                    FROM 
                    WHERE 
                    ORDER BY
                """
                ),
                {"user_id": user_id, "limit": limit}
            )

    def get_unseen_tags_posts(
        self,
        user_id: UUID,
        limit: int,
    ) -> list[tuple[UUID, float]]:
        with self.session_provider.session() as session:
            session.execute(
                text("""
                    SELECT 
                    FROM 
                    WHERE 
                    ORDER BY
                """
                ),
                {"user_id": user_id, "limit": limit}
            )