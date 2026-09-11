from uuid import UUID

from sqlalchemy import select
from sqlalchemy.dialects.postgresql import insert as pg_insert

from post.entity.user_post_interactions_entity import UserPostInteractionsRecord
from post.model.user_post_interactions import UserPostInteractions
from post.repository.user_post_interactions_repository import UserPostInteractionsRepository
from shared.config.database import SQLAlchemySessionProvider
from shared.repository.impl.mappers import (
    user_post_interactions_from_record,
    user_post_interactions_values,
)

_PK = {"post_id", "user_id"}


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
            stmt = pg_insert(UserPostInteractionsRecord).values(**values)
            session.execute(
                stmt.on_conflict_do_update(
                    index_elements=["post_id", "user_id"],
                    set_={key: getattr(stmt.excluded, key) for key in values if key not in _PK},
                )
            )

    def get_all_by_user(
        self,
        post_ids: list[UUID],
        user_id: UUID,
    ) -> dict[UUID, UserPostInteractions]:
        if not post_ids:
            return {}
        with self.session_provider.session() as session:
            records = session.scalars(
                select(UserPostInteractionsRecord).where(
                    UserPostInteractionsRecord.user_id == user_id,
                    UserPostInteractionsRecord.post_id.in_(post_ids),
                )
            ).all()
            return {r.post_id: user_post_interactions_from_record(r) for r in records}

    def save_all(self, interactions: list[UserPostInteractions]) -> None:
        if not interactions:
            return
        values_list = [user_post_interactions_values(i) for i in interactions]
        with self.session_provider.session() as session:
            stmt = pg_insert(UserPostInteractionsRecord).values(values_list)
            session.execute(
                stmt.on_conflict_do_update(
                    index_elements=["post_id", "user_id"],
                    set_={key: getattr(stmt.excluded, key) for key in values_list[0] if key not in _PK},
                )
            )
