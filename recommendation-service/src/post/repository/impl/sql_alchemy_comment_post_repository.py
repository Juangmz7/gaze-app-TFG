from uuid import UUID

from sqlalchemy import delete, select
from sqlalchemy.dialects.postgresql import insert as pg_insert

from post.entity.comment_post_entity import CommentPostRecord
from post.repository.comment_post_repository import CommentPostRepository
from shared.config.database import SQLAlchemySessionProvider


class SqlAlchemyCommentPostRepository(CommentPostRepository):
    def __init__(self, session_provider: SQLAlchemySessionProvider):
        self.session_provider = session_provider

    def save_comment_post(self, comment_id: UUID, post_id: UUID) -> None:
        values = {"comment_id": comment_id, "post_id": post_id}
        with self.session_provider.session() as session:
            stmt = pg_insert(CommentPostRecord).values(**values)
            session.execute(
                stmt.on_conflict_do_update(
                    index_elements=["comment_id"],
                    set_={"post_id": stmt.excluded.post_id},
                )
            )

    def find_post_id_by_comment_id(self, comment_id: UUID) -> UUID | None:
        with self.session_provider.session() as session:
            return session.scalar(
                select(CommentPostRecord.post_id)
                .where(CommentPostRecord.comment_id == comment_id)
            )

    def delete_comment_post(self, comment_id: UUID) -> None:
        with self.session_provider.session() as session:
            session.execute(
                delete(CommentPostRecord).where(CommentPostRecord.comment_id == comment_id)
            )
