from uuid import UUID

from sqlalchemy.dialects.postgresql import insert as pg_insert

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

_PK = {"comment_id", "user_id"}


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
            stmt = pg_insert(UserPostCommentInteractionRecord).values(**values)
            session.execute(
                stmt.on_conflict_do_update(
                    index_elements=["comment_id", "user_id"],
                    set_={key: getattr(stmt.excluded, key) for key in values if key not in _PK},
                )
            )

