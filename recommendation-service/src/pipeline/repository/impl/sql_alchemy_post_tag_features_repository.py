from uuid import UUID

from sqlalchemy import insert, select, update
from sqlalchemy.dialects.postgresql import insert as pg_insert

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

    def get_post_tag_features(self, user_id: UUID, tags: list[str]) -> list[PostTagFeatures]:
        return self._get_post_tag_features(user_id, tags, for_update=False)

    def get_post_tag_features_for_update(
        self,
        user_id: UUID,
        tags: list[str],
    ) -> list[PostTagFeatures]:
        return self._get_post_tag_features(user_id, tags, for_update=True)

    def getPostsTagsFeatures(self, user_id: UUID, tags: list[str]) -> list[PostTagFeatures]:
        return self.get_post_tag_features(user_id, tags)

    def _get_post_tag_features(
        self,
        user_id: UUID,
        tags: list[str],
        *,
        for_update: bool,
    ) -> list[PostTagFeatures]:
        if not tags:
            return []

        with self.session_provider.session() as session:
            statement = (
                select(PostTagFeaturesRecord)
                .where(
                    PostTagFeaturesRecord.user_id == user_id,
                    PostTagFeaturesRecord.tag_name.in_(tags),
                )
                .order_by(PostTagFeaturesRecord.tag_name)
            )
            if for_update:
                statement = statement.with_for_update()

            records = session.scalars(statement).all()
            return [post_tag_features_from_record(record) for record in records]

    def create_if_absent(
        self,
        post_tag_features: list[PostTagFeatures],
    ) -> None:
        if not post_tag_features:
            return

        values = [
            post_tag_features_values(features)
            for features in post_tag_features
        ]

        with self.session_provider.session() as session:
            session.execute(
                pg_insert(PostTagFeaturesRecord)
                .values(values)
                .on_conflict_do_nothing(
                    index_elements=["user_id", "tag_name"],
                )
            )

    def save_all(
        self,
        post_tag_features: list[PostTagFeatures],
    ) -> None:
        if not post_tag_features:
            return

        values = [
            post_tag_features_values(features)
            for features in post_tag_features
        ]

        with self.session_provider.session() as session:
            stmt = pg_insert(PostTagFeaturesRecord).values(values)

            session.execute(
                stmt.on_conflict_do_update(
                    index_elements=["user_id", "tag_name"],
                    set_={
                        key: getattr(stmt.excluded, key)
                        for key in values[0]
                        if key not in {"user_id", "tag_name"}
                    },
                )
            )
