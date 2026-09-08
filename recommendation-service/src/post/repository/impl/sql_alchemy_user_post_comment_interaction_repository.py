from uuid import UUID

from sqlalchemy import insert, update

from post.entity.user_post_comment_interaction_entity import (
    UserPostCommentInteractionRecord,
)
from post.model.user_post_comment_interaction import UserPostCommentInteraction
from post.repository.user_post_comment_interaction_repository import (
    UserPostCommentInteractionRepository,
)
from shared.config.database import SQLAlchemySessionProvider
from shared.repository.impl.mappers import (
    user_post_comment_interaction_from_record,
    user_post_comment_interaction_values,
)


class SqlAlchemyUserPostCommentInteractionRepository(
    UserPostCommentInteractionRepository,
):
    def __init__(self, session_provider: SQLAlchemySessionProvider):
        self.session_provider = session_provider

    def get(self, comment_id: UUID, user_id: UUID) -> UserPostCommentInteraction | None:
        with self.session_provider.session() as session:
            record = session.get(UserPostCommentInteractionRecord, (comment_id, user_id))
            return (
                user_post_comment_interaction_from_record(record)
                if record is not None
                else None
            )

    def save(self, interaction: UserPostCommentInteraction) -> None:
        values = user_post_comment_interaction_values(interaction)
        with self.session_provider.session() as session:
            result = session.execute(
                update(UserPostCommentInteractionRecord)
                .where(
                    UserPostCommentInteractionRecord.comment_id == interaction.comment_id,
                    UserPostCommentInteractionRecord.user_id == interaction.user_id,
                )
                .values(**values)
            )
            if result.rowcount == 0:
                session.execute(insert(UserPostCommentInteractionRecord).values(**values))
