from uuid import UUID

from sqlalchemy import insert, select, update

from pipeline.entity.post.post_tag_features_entity import PostTagFeaturesRecord
from pipeline.model.post.post_tag_features import PostTagFeatures
from pipeline.repository.post_tag_features_repository import PostTagFeaturesRepository
from shared.config.database import SQLAlchemySessionProvider
from shared.repository.impl.mappers import (
    post_tag_features_from_record,
    post_tag_features_values,
)


class SqlAlchemyPostTagFeaturesRepository(PostTagFeaturesRepository):
    def __init__(self, session_provider: SQLAlchemySessionProvider):
        self.session_provider = session_provider

    def getPostsTagsFeatures(self, user_id: UUID, tags: list[str]) -> list[PostTagFeatures]:
        if not tags:
            return []

        with self.session_provider.session() as session:
            records = session.scalars(
                select(PostTagFeaturesRecord).where(
                    PostTagFeaturesRecord.user_id == user_id,
                    PostTagFeaturesRecord.tag_name.in_(tags),
                )
            ).all()
            return [post_tag_features_from_record(record) for record in records]

    def save_all(self, post_tag_features: list[PostTagFeatures]) -> None:
        with self.session_provider.session() as session:
            for features in post_tag_features:
                values = post_tag_features_values(features)
                result = session.execute(
                    update(PostTagFeaturesRecord)
                    .where(
                        PostTagFeaturesRecord.user_id == features.user_id,
                        PostTagFeaturesRecord.tag_name == features.tag_name,
                    )
                    .values(**values)
                )
                if result.rowcount == 0:
                    session.execute(insert(PostTagFeaturesRecord).values(**values))
