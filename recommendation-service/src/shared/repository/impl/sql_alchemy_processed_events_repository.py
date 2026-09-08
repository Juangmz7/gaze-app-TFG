from datetime import datetime, timezone
from uuid import UUID

from sqlalchemy import exists, insert, select, update

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
            result = session.execute(
                update(ProcessedEventRecord)
                .where(
                    ProcessedEventRecord.event_id == event_id,
                    ProcessedEventRecord.correlation_id == correlation_id,
                )
                .values(**values)
            )
            if result.rowcount == 0:
                session.execute(insert(ProcessedEventRecord).values(**values))
