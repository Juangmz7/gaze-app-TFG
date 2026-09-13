from uuid import UUID

from sqlalchemy import delete, insert, select, update

from pipeline.entity.post.post_features_entity import PostFeaturesRecord
from post.entity.collab_entity import CollabRecord
from post.model.collab import Collab
from post.repository.collab_repository import CollabRepository
from shared.config.database import SQLAlchemySessionProvider
from shared.repository.impl.mappers import collab_from_record, collab_values


class SqlAlchemyCollabRepository(CollabRepository):
    def __init__(self, session_provider: SQLAlchemySessionProvider):
        self.session_provider = session_provider

    def get(self, collab_id: UUID) -> Collab | None:
        with self.session_provider.session() as session:
            record = session.get(CollabRecord, collab_id)
            return collab_from_record(record) if record is not None else None

    def save(self, collab: Collab) -> None:
        values = collab_values(collab)
        with self.session_provider.session() as session:
            result = session.execute(
                update(CollabRecord)
                .where(CollabRecord.collab_id == collab.collab_id)
                .values(**values)
            )
            if result.rowcount == 0:
                session.execute(insert(CollabRecord).values(**values))

    def delete(self, collab_id: UUID) -> None:
        with self.session_provider.session() as session:
            session.execute(delete(CollabRecord).where(CollabRecord.collab_id == collab_id))

    def find_posts_id_by_collab_id(self, collab_id: UUID) -> list[UUID]:
        with self.session_provider.session() as session:
            return session.scalars(
                select(PostFeaturesRecord.post_id)
                .where(PostFeaturesRecord.collab_id == collab_id)
            ).all()
