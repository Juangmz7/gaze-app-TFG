from uuid import UUID

from sqlalchemy import insert, update

from post.entity.user_post_interactions_entity import UserPostInteractionsRecord
from post.model.user_post_interactions import UserPostInteractions
from post.repository.user_post_interactions_repository import UserPostInteractionsRepository
from shared.config.database import SQLAlchemySessionProvider
from shared.repository.impl.mappers import (
    user_post_interactions_from_record,
    user_post_interactions_values,
)


class SqlAlchemyUserPostInteractionsRepository(UserPostInteractionsRepository):
    def __init__(self, session_provider: SQLAlchemySessionProvider):
        self.session_provider = session_provider

    def get(self, post_id: UUID, user_id: UUID) -> UserPostInteractions | None:
        with self.session_provider.session() as session:
            record = session.get(UserPostInteractionsRecord, (post_id, user_id))
            return user_post_interactions_from_record(record) if record is not None else None

    def save(self, interactions: UserPostInteractions) -> None:
        values = user_post_interactions_values(interactions)
        with self.session_provider.session() as session:
            result = session.execute(
                update(UserPostInteractionsRecord)
                .where(
                    UserPostInteractionsRecord.post_id == interactions.post_id,
                    UserPostInteractionsRecord.user_id == interactions.user_id,
                )
                .values(**values)
            )
            if result.rowcount == 0:
                session.execute(insert(UserPostInteractionsRecord).values(**values))
