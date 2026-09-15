from datetime import datetime
from uuid import UUID

from sqlalchemy import delete
from sqlalchemy.dialects.postgresql import insert as pg_insert

from pipeline.entity.post.post_interaction_features_entity import PostInteractionFeaturesRecord
from pipeline.model.post.post_interaction_features import PostInteractionFeatures
from pipeline.repository.post_interaction_features_repository import PostInteractionFeaturesRepository
from shared.config.database import SQLAlchemySessionProvider
from shared.repository.impl.mappers import post_interaction_features_from_record, post_interaction_features_values


class SqlAlchemyPostInteractionFeaturesRepository(PostInteractionFeaturesRepository):
    def __init__(self, session_provider: SQLAlchemySessionProvider):
        self.session_provider = session_provider

    def get_for_update(self, post_id: UUID) -> PostInteractionFeatures | None:
        with self.session_provider.session() as session:
            record = (
                session.query(PostInteractionFeaturesRecord)
                .with_for_update()
                .filter(PostInteractionFeaturesRecord.post_id == post_id)
                .first()
            )
            if record is not None:
                return post_interaction_features_from_record(record)
            return None

    def save(self, features: PostInteractionFeatures) -> None:
        values = post_interaction_features_values(features)
        with self.session_provider.session() as session:
            stmt = pg_insert(PostInteractionFeaturesRecord).values(**values)
            session.execute(
                stmt.on_conflict_do_update(
                    index_elements=["post_id"],
                    set_={key: getattr(stmt.excluded, key) for key in values if key != "post_id"},
                )
            )

    def create_empty(self, post_id: UUID, created_at: datetime) -> None:
        values = {
            "post_id": post_id,
            "impressions": 0,
            "views": 0,
            "likes": 0,
            "comments": 0,
            "shares": 0,
            "fast_skips": 0,
            "collab_requests": 0,
            "collab_requests_accepted": 0,
            "watch_time_average_percent": 0.0,
            "watch_time": 0.0,
            "last_updated_at": created_at,
            "decayed_engagement_score": 0.0,
        }
        with self.session_provider.session() as session:
            stmt = pg_insert(PostInteractionFeaturesRecord).values(**values)
            session.execute(stmt.on_conflict_do_nothing(index_elements=["post_id"]))

    def delete(self, post_id: UUID) -> None:
        with self.session_provider.session() as session:
            session.execute(delete(PostInteractionFeaturesRecord).where(PostInteractionFeaturesRecord.post_id == post_id))
