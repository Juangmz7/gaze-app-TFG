import asyncio
import logging
import time
from contextlib import suppress
from datetime import datetime, timedelta, timezone
from typing import Any

from aiormq.abc import DeliveredMessage
from faststream.rabbit import RabbitBroker
from sqlalchemy import text

from shared.config.database import SQLAlchemySessionProvider

logger = logging.getLogger(__name__)

# Atomic claim: only one relay instance gets each row (SKIP LOCKED), and rows
# whose relay died mid-publish are reclaimed once their lock expires.
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

MARK_PROCESSED_SQL = text("""
    UPDATE outbox_events
       SET status = 'PROCESSED', processed_at = now(), locked_at = NULL, last_error = NULL
     WHERE id = :id
""")

MARK_FAILED_SQL = text("""
    UPDATE outbox_events
       SET status = :status, last_error = :error, locked_at = NULL
     WHERE id = :id
""")

DELETE_PROCESSED_SQL = text("""
    DELETE FROM outbox_events
     WHERE status = 'PROCESSED' AND processed_at < :processed_before
""")

MAX_ERROR_LENGTH = 1000


class OutboxRelayWorker:
    """Publishes outbox_events rows with publisher confirms (at-least-once):
    claim one row, publish, wait for the broker ack, then mark it PROCESSED.
    Consumers must deduplicate by message id (= event id)."""

    def __init__(
        self,
        session_provider: SQLAlchemySessionProvider,
        broker: RabbitBroker,
        *,
        interval_s: float = 5.0,
        batch_size: int = 100,
        max_attempts: int = 10,
        lock_timeout_s: float = 60.0,
        confirm_timeout_s: float = 5.0,
        processed_retention: timedelta = timedelta(days=7),
        cleanup_interval_s: float = 3600.0,
    ):
        self._session_provider = session_provider
        self._broker = broker
        self._interval_s = interval_s
        self._batch_size = batch_size
        self._max_attempts = max_attempts
        self._lock_timeout = timedelta(seconds=lock_timeout_s)
        self._confirm_timeout_s = confirm_timeout_s
        self._processed_retention = processed_retention
        self._cleanup_interval_s = cleanup_interval_s
        self._last_cleanup = 0.0
        self._task: asyncio.Task | None = None

    def start(self) -> None:
        self._task = asyncio.create_task(self._run(), name="outbox-relay")

    async def stop(self) -> None:
        if self._task is not None:
            self._task.cancel()
            with suppress(asyncio.CancelledError):
                await self._task
            self._task = None

    async def _run(self) -> None:
        while True:
            try:
                await self.relay_batch()
                await self._cleanup_if_due()
            except asyncio.CancelledError:
                raise
            except Exception:
                logger.exception("Outbox relay iteration failed")
            await asyncio.sleep(self._interval_s)

    async def relay_batch(self) -> None:
        """Publishes up to batch_size rows, oldest first. Stops at the first
        failed publish so later events are not sent ahead of it."""
        # Claiming consumes an attempt, so nothing is claimed while the broker is
        # unreachable: an outage longer than max_attempts cycles must not move
        # events to FAILED.
        if not await self._broker.ping(timeout=self._confirm_timeout_s):
            logger.warning("Outbox relay skipped, broker unavailable")
            return

        for _ in range(self._batch_size):
            row = await asyncio.to_thread(self._claim_next)
            if row is None:
                return
            try:
                await self._publish(row)
            except Exception as exc:
                await asyncio.to_thread(self._mark_failed, row, exc)
                return
            await asyncio.to_thread(self._mark_processed, row.id)

    async def delete_processed(self) -> int:
        return await asyncio.to_thread(self._delete_processed)

    async def _publish(self, row: Any) -> None:
        # Raises DeliveryError on a broker nack and TimeoutError without a confirm
        result = await self._broker.publish(
            row.payload,
            exchange=row.exchange,
            routing_key=row.routing_key,
            message_id=str(row.id),
            correlation_id=str(row.correlation_id),
            message_type=row.event_type,
            persist=True,
            mandatory=True,
            timeout=self._confirm_timeout_s,
        )
        # Acked but returned: no queue is bound yet, so retrying cannot help
        if isinstance(result, DeliveredMessage):
            logger.warning(
                "Outbox event was unroutable and is marked as processed: id=%s type=%s exchange=%s routing_key=%s",
                row.id, row.event_type, row.exchange, row.routing_key,
            )

    async def _cleanup_if_due(self) -> None:
        now = time.monotonic()
        if now - self._last_cleanup < self._cleanup_interval_s:
            return
        self._last_cleanup = now
        deleted = await self.delete_processed()
        if deleted:
            logger.info("Deleted %s processed outbox events", deleted)

    # --- DB access: sync SQLAlchemy, always run in a worker thread so the event loop is never blocked ---

    def _claim_next(self) -> Any:
        locked_before = datetime.now(timezone.utc) - self._lock_timeout
        with self._session_provider.session() as session:
            return session.execute(CLAIM_SQL, {"locked_before": locked_before}).first()

    def _mark_processed(self, event_id) -> None:
        with self._session_provider.session() as session:
            session.execute(MARK_PROCESSED_SQL, {"id": event_id})

    def _mark_failed(self, row: Any, exc: Exception) -> None:
        exhausted = row.attempts >= self._max_attempts
        if exhausted:
            logger.error(
                "Outbox event moved to FAILED after %s attempts: id=%s type=%s",
                row.attempts, row.id, row.event_type, exc_info=exc,
            )
        else:
            logger.warning(
                "Outbox publish failed: id=%s type=%s attempt=%s",
                row.id, row.event_type, row.attempts, exc_info=exc,
            )

        with self._session_provider.session() as session:
            session.execute(
                MARK_FAILED_SQL,
                {
                    "id": row.id,
                    "status": "FAILED" if exhausted else "PENDING",
                    "error": (str(exc) or type(exc).__name__)[:MAX_ERROR_LENGTH],
                },
            )

    def _delete_processed(self) -> int:
        processed_before = datetime.now(timezone.utc) - self._processed_retention
        with self._session_provider.session() as session:
            return session.execute(DELETE_PROCESSED_SQL, {"processed_before": processed_before}).rowcount
