from datetime import datetime
from uuid import UUID

from sqlalchemy import text

from shared.config.database import SQLAlchemySessionProvider
from shared.entity.outbox_event_entity import OutboxEventRecord
from shared.enum.outbox_event_status import OutboxEventStatus
from shared.model.outbox_event import OutboxEvent
from shared.repository.outbox_event_repository import OutboxEventRepository

# Atomic claim: only one relay gets each row (SKIP LOCKED), and rows whose
# relay died mid-publish are reclaimed once their lock expires.
CLAIM_SQL = text("""
    UPDATE outbox_events
       SET status = 'PROCESSING', locked_at = now(), attempts = attempts + 1
     WHERE id = (
           SELECT id FROM outbox_events
            WHERE status = 'PENDING'
               OR (status = 'PROCESSING' AND locked_at < :locked_before)
            ORDER BY created_at
            LIMIT 1
            FOR UPDATE SKIP LOCKED)
    RETURNING id, correlation_id, event_type, exchange, routing_key, payload, attempts
""")

# Claims one specific row, only if it is still PENDING (immediate send after commit)
CLAIM_IF_PENDING_SQL = text("""
    UPDATE outbox_events
       SET status = 'PROCESSING', locked_at = now(), attempts = attempts + 1
     WHERE id = :id AND status = 'PENDING'
    RETURNING id, correlation_id, event_type, exchange, routing_key, payload, attempts
""")

MARK_PROCESSED_SQL = text("""
    UPDATE outbox_events
       SET status = 'PROCESSED', processed_at = now(), locked_at = NULL, last_error = NULL
     WHERE id = :id
""")

# Only the claim identified by attempts (every claim increments it) may release
# the row: a relay whose lock expired, so the row was reclaimed or already
# PROCESSED by another relay, matches nothing instead of overwriting the newer
# state. MARK_PROCESSED_SQL needs no such guard: a confirmed publish is true
# whoever reports it.
MARK_FAILED_SQL = text("""
    UPDATE outbox_events
       SET status = :status, last_error = :error, locked_at = NULL
     WHERE id = :id AND status = 'PROCESSING' AND attempts = :attempts
""")

DELETE_PROCESSED_SQL = text("""
    DELETE FROM outbox_events
     WHERE status = 'PROCESSED' AND processed_at < :processed_before
""")


class SqlAlchemyOutboxEventRepository(OutboxEventRepository):
    def __init__(self, session_provider: SQLAlchemySessionProvider):
        self.session_provider = session_provider

    def add(self, event: OutboxEvent) -> None:
        # session_provider joins the caller's transaction_manager.transaction()
        with self.session_provider.session() as session:
            session.add(
                OutboxEventRecord(
                    id=event.id,
                    correlation_id=event.correlation_id,
                    event_type=event.event_type,
                    exchange=event.exchange,
                    routing_key=event.routing_key,
                    payload=event.payload,
                )
            )

    def claim_next(self, locked_before: datetime) -> OutboxEvent | None:
        with self.session_provider.session() as session:
            row = session.execute(CLAIM_SQL, {"locked_before": locked_before}).first()
        return self._to_claimed_event(row)

    def claim_if_pending(self, event_id: UUID) -> OutboxEvent | None:
        with self.session_provider.session() as session:
            row = session.execute(CLAIM_IF_PENDING_SQL, {"id": event_id}).first()
        return self._to_claimed_event(row)

    @staticmethod
    def _to_claimed_event(row) -> OutboxEvent | None:
        if row is None:
            return None
        return OutboxEvent(
            id=row.id,
            correlation_id=row.correlation_id,
            event_type=row.event_type,
            exchange=row.exchange,
            routing_key=row.routing_key,
            payload=row.payload,
            attempts=row.attempts,
        )

    def mark_processed(self, event_id: UUID) -> None:
        with self.session_provider.session() as session:
            session.execute(MARK_PROCESSED_SQL, {"id": event_id})

    def mark_failed_attempt(
        self, event_id: UUID, attempts: int, status: OutboxEventStatus, last_error: str
    ) -> bool:
        with self.session_provider.session() as session:
            result = session.execute(
                MARK_FAILED_SQL,
                {"id": event_id, "attempts": attempts, "status": str(status), "error": last_error},
            )
        return result.rowcount == 1

    def delete_processed_before(self, processed_before: datetime) -> int:
        with self.session_provider.session() as session:
            return session.execute(DELETE_PROCESSED_SQL, {"processed_before": processed_before}).rowcount
