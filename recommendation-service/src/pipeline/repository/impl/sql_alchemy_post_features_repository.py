from uuid import UUID

from sqlalchemy import delete, insert, update

from pipeline.entity.post.post_features_entity import PostFeaturesRecord
from pipeline.model.post.post_features import PostFeatures
from pipeline.repository.post_features_repository import PostFeaturesRepository
from shared.config.database import SQLAlchemySessionProvider
from shared.repository.impl.mappers import post_features_from_record, post_features_values


class SqlAlchemyPostFeaturesRepository(PostFeaturesRepository):
    def __init__(self, session_provider: SQLAlchemySessionProvider):
        self.session_provider = session_provider

    def get_post_features(self, post_id: UUID) -> PostFeatures | None:
        with self.session_provider.session() as session:
            record = session.get(PostFeaturesRecord, post_id)
            return post_features_from_record(record) if record is not None else None

    def save(self, post_features: PostFeatures) -> None:
        values = post_features_values(post_features)
        with self.session_provider.session() as session:
            result = session.execute(
                update(PostFeaturesRecord)
                .where(PostFeaturesRecord.post_id == post_features.post_id)
                .values(**values)
            )
            if result.rowcount == 0:
                session.execute(insert(PostFeaturesRecord).values(**values))

    def delete(self, post_id: UUID) -> None:
        with self.session_provider.session() as session:
            session.execute(delete(PostFeaturesRecord).where(PostFeaturesRecord.post_id == post_id))

    def update_post_collab(
        self,
        post_id: UUID,
        collab_id: UUID | None,
        collab_title: str | None,
    ) -> None:
        with self.session_provider.session() as session:
            session.execute(
                update(PostFeaturesRecord)
                .where(PostFeaturesRecord.post_id == post_id)
                .values(collab_id=collab_id, collab_title=collab_title)
            )
