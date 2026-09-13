from uuid import UUID

from sqlalchemy import select
from sqlalchemy.dialects.postgresql import insert as pg_insert

from pipeline.entity.user.user_creator_features_entity import UserCreatorFeaturesRecord
from pipeline.model.user.user_creator_features import UserCreatorFeatures
from pipeline.repository.user_creator_features_repository import UserCreatorFeaturesRepository
from shared.config.database import SQLAlchemySessionProvider
from shared.repository.impl.mappers import (
    user_creator_features_from_record,
    user_creator_features_values,
)


class SqlAlchemyUserCreatorFeaturesRepository(UserCreatorFeaturesRepository):
    def __init__(self, session_provider: SQLAlchemySessionProvider):
        self.session_provider = session_provider

    def get_user_creator_features(
        self,
        user_id: UUID,
        creator_id: UUID,
    ) -> UserCreatorFeatures | None:
        return self._get_user_creator_features(user_id, creator_id, for_update=False)

    def get_user_creator_features_for_update(
        self,
        user_id: UUID,
        creator_id: UUID,
    ) -> UserCreatorFeatures | None:
        return self._get_user_creator_features(user_id, creator_id, for_update=True)

    def _get_user_creator_features(
        self,
        user_id: UUID,
        creator_id: UUID,
        *,
        for_update: bool,
    ) -> UserCreatorFeatures | None:
        with self.session_provider.session() as session:
            statement = (
                select(UserCreatorFeaturesRecord)
                .where(
                    UserCreatorFeaturesRecord.user_id == user_id,
                    UserCreatorFeaturesRecord.creator_id == creator_id,
                )
                .limit(1)
            )
            if for_update:
                statement = statement.with_for_update()

            record = session.scalars(statement).first()

            if record is not None:
                return user_creator_features_from_record(record)
            else:
                return None

    def create_if_absent(self, user_creator_features: UserCreatorFeatures) -> None:
        values = user_creator_features_values(user_creator_features)
        with self.session_provider.session() as session:
            session.execute(
                pg_insert(UserCreatorFeaturesRecord)
                .values(**values)
                .on_conflict_do_nothing(
                    index_elements=["user_id", "creator_id"],
                )
            )

    def save(self, user_creator_features: UserCreatorFeatures) -> None:
        values = user_creator_features_values(user_creator_features)
        with self.session_provider.session() as session:
            stmt = pg_insert(UserCreatorFeaturesRecord).values(**values)
            session.execute(
                stmt.on_conflict_do_update(
                    index_elements=["user_id", "creator_id"],
                    set_={key: getattr(stmt.excluded, key) for key in values if key not in {"user_id", "creator_id"}},
                )
            )
