from datetime import datetime, timezone
from uuid import UUID

from sqlalchemy import exists, select
from sqlalchemy.dialects.postgresql import insert as pg_insert

from shared.config.database import SQLAlchemySessionProvider
from shared.entity.processed_event_entity import ProcessedEventRecord
from shared.repository.processed_events_repository import ProcessedEventsRepository


class SqlAlchemyProcessedEventsRepository(ProcessedEventsRepository):
    def __init__(self, session_provider: SQLAlchemySessionProvider):
        self.session_provider = session_provider

    def isAlreadyProcessed(self, event_id: UUID, correlation_id: UUID) -> bool:
        with self.session_provider.session() as session:
            return bool(
                session.scalar(
                    select(
                        exists().where(
                            ProcessedEventRecord.event_id == event_id,
                            ProcessedEventRecord.correlation_id == correlation_id,
                        )
                    )
                )
            )

    def setEventAsProcessed(self, event_id: UUID, correlation_id: UUID, event_name: str) -> None:
        values = {
            "event_id": event_id,
            "correlation_id": correlation_id,
            "event_name": event_name,
            "processed_at": datetime.now(timezone.utc),
        }
        with self.session_provider.session() as session:
            stmt = pg_insert(ProcessedEventRecord).values(**values)
            session.execute(
                stmt.on_conflict_do_update(
                    index_elements=["event_id", "correlation_id"],
                    set_={key: getattr(stmt.excluded, key) for key in values if key not in {"event_id", "correlation_id"}},
                )
            )

