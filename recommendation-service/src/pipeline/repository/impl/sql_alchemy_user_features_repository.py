from uuid import UUID

from sqlalchemy import insert, select, update

from pipeline.entity.user.user_features_entity import UserFeaturesRecord
from pipeline.model.user.user_features import UserFeatures
from pipeline.repository.user_features_repository import UserFeaturesRepository
from shared.config.database import SQLAlchemySessionProvider
from shared.repository.impl.mappers import user_features_from_record, user_features_values


class SqlAlchemyUserFeaturesRepository(UserFeaturesRepository):
    def __init__(self, session_provider: SQLAlchemySessionProvider):
        self.session_provider = session_provider

    def get_user_features(self, user_id: UUID) -> UserFeatures | None:
        return self._get_user_features(user_id, for_update=False)

    def get_user_features_for_update(self, user_id: UUID) -> UserFeatures | None:
        return self._get_user_features(user_id, for_update=True)

    def _get_user_features(self, user_id: UUID, *, for_update: bool) -> UserFeatures | None:
        with self.session_provider.session() as session:
            statement = select(UserFeaturesRecord).where(UserFeaturesRecord.user_id == user_id)
            if for_update:
                statement = statement.with_for_update()

            record = session.scalar(statement)
            return user_features_from_record(record) if record is not None else None

    def save(self, user_features: UserFeatures) -> None:
        values = user_features_values(user_features)
        with self.session_provider.session() as session:
            result = session.execute(
                update(UserFeaturesRecord)
                .where(UserFeaturesRecord.user_id == user_features.user_id)
                .values(**values)
            )
            if result.rowcount == 0:
                session.execute(insert(UserFeaturesRecord).values(**values))
