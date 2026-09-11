from uuid import UUID

from sqlalchemy import delete, update
from sqlalchemy.dialects.postgresql import insert as pg_insert

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
            stmt = pg_insert(PostFeaturesRecord).values(**values)
            session.execute(
                stmt.on_conflict_do_update(
                    index_elements=["post_id"],
                    set_={key: getattr(stmt.excluded, key) for key in values if key != "post_id"},
                )
            )

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

    def clear_collab_for_posts(self, post_ids: list[UUID]) -> None:
        if not post_ids:
            return
        with self.session_provider.session() as session:
            session.execute(
                update(PostFeaturesRecord)
                .where(PostFeaturesRecord.post_id.in_(post_ids))
                .values(collab_id=None, collab_title=None)
            )
