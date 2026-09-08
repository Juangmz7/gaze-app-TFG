from uuid import UUID

from sqlalchemy import delete, insert, select, update

from post.entity.comment_post_entity import CommentPostRecord
from post.repository.comment_post_repository import CommentPostRepository
from shared.config.database import SQLAlchemySessionProvider


class SqlAlchemyCommentPostRepository(CommentPostRepository):
    def __init__(self, session_provider: SQLAlchemySessionProvider):
        self.session_provider = session_provider

    def save_comment_post(self, comment_id: UUID, post_id: UUID) -> None:
        values = {"comment_id": comment_id, "post_id": post_id}
        with self.session_provider.session() as session:
            result = session.execute(
                update(CommentPostRecord)
                .where(CommentPostRecord.comment_id == comment_id)
                .values(**values)
            )
            if result.rowcount == 0:
                session.execute(insert(CommentPostRecord).values(**values))

    def find_post_id_by_comment_id(self, comment_id: UUID) -> UUID | None:
        with self.session_provider.session() as session:
            return session.scalar(
                select(CommentPostRecord.post_id)
                .where(CommentPostRecord.comment_id == comment_id)
                .limit(1)
            )

    def delete_comment_post(self, comment_id: UUID) -> None:
        with self.session_provider.session() as session:
            session.execute(
                delete(CommentPostRecord).where(CommentPostRecord.comment_id == comment_id)
            )
