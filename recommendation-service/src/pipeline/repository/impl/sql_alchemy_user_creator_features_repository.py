from uuid import UUID

from sqlalchemy import insert, select, update

from pipeline.entity.post.post_features_entity import PostFeaturesRecord
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
        post_id: UUID,
        user_id: UUID,
    ) -> UserCreatorFeatures | None:
        with self.session_provider.session() as session:
            record = session.scalars(
                select(UserCreatorFeaturesRecord)
                .join(
                    PostFeaturesRecord,
                    PostFeaturesRecord.creator_id == UserCreatorFeaturesRecord.creator_id,
                )
                .where(
                    PostFeaturesRecord.post_id == post_id,
                    UserCreatorFeaturesRecord.user_id == user_id,
                )
                .limit(1)
            ).first()
            return user_creator_features_from_record(record) if record is not None else None

    def save(self, user_creator_features: UserCreatorFeatures) -> None:
        values = user_creator_features_values(user_creator_features)
        with self.session_provider.session() as session:
            result = session.execute(
                update(UserCreatorFeaturesRecord)
                .where(
                    UserCreatorFeaturesRecord.user_id == user_creator_features.user_id,
                    UserCreatorFeaturesRecord.creator_id == user_creator_features.creator_id,
                )
                .values(**values)
            )
            if result.rowcount == 0:
                session.execute(insert(UserCreatorFeaturesRecord).values(**values))
