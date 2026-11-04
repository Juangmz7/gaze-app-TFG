import asyncio
import logging
import time
from contextlib import suppress
from datetime import datetime, timedelta, timezone
from uuid import UUID

from aiormq.abc import DeliveredMessage
from faststream.rabbit import RabbitBroker

from shared.enum.outbox_event_status import OutboxEventStatus
from shared.model.outbox_event import OutboxEvent
from shared.repository.outbox_event_repository import OutboxEventRepository

logger = logging.getLogger(__name__)

MAX_ERROR_LENGTH = 1000


class OutboxRelayWorker:
    """Publishes outbox events with publisher confirms (at-least-once):
    claim one event, publish, wait for the broker ack, then mark it PROCESSED.
    Consumers must deduplicate by message id (= event id)."""

    def __init__(
        self,
        outbox_event_repository: OutboxEventRepository,
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
        self._repository = outbox_event_repository
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
            event = await asyncio.to_thread(self._claim_next)
            if event is None:
                return
            try:
                await self._publish(event)
            except Exception as exc:
                await asyncio.to_thread(self._mark_failed, event, exc)
                return
            try:
                await asyncio.to_thread(self._mark_processed, event.id)
            except Exception:
                # Delivered but still PROCESSING: it is reclaimed and sent again once the lock expires
                logger.exception(
                    "Outbox event was published but could not be marked PROCESSED; it will be republished "
                    "as a duplicate after the %.0fs lock timeout: id=%s type=%s attempt=%s",
                    self._lock_timeout.total_seconds(), event.id, event.event_type, event.attempts,
                )
                return  # the database is failing: stop the batch

    async def delete_processed(self) -> int:
        return await asyncio.to_thread(self._delete_processed)

    async def _publish(self, event: OutboxEvent) -> None:
        # Raises DeliveryError on a broker nack and TimeoutError without a confirm
        result = await self._broker.publish(
            event.payload,
            exchange=event.exchange,
            routing_key=event.routing_key,
            message_id=str(event.id),
            correlation_id=str(event.correlation_id),
            message_type=event.event_type,
            persist=True,
            mandatory=True,
            timeout=self._confirm_timeout_s,
        )
        # Acked but returned: no queue is bound yet, so retrying cannot help
        if isinstance(result, DeliveredMessage):
            logger.warning(
                "Outbox event was unroutable and is marked as processed: id=%s type=%s exchange=%s routing_key=%s",
                event.id, event.event_type, event.exchange, event.routing_key,
            )

    async def _cleanup_if_due(self) -> None:
        now = time.monotonic()
        if now - self._last_cleanup < self._cleanup_interval_s:
            return
        self._last_cleanup = now
        deleted = await self.delete_processed()
        if deleted:
            logger.info("Deleted %s processed outbox events", deleted)

    # --- Repository calls are blocking: always run them in a worker thread so the event loop is never blocked ---

    def _claim_next(self) -> OutboxEvent | None:
        return self._repository.claim_next(datetime.now(timezone.utc) - self._lock_timeout)

    def _mark_processed(self, event_id: UUID) -> None:
        self._repository.mark_processed(event_id)

    def _mark_failed(self, event: OutboxEvent, exc: Exception) -> None:
        exhausted = event.attempts >= self._max_attempts
        if exhausted:
            logger.error(
                "Outbox event moved to FAILED after %s attempts: id=%s type=%s",
                event.attempts, event.id, event.event_type, exc_info=exc,
            )
        else:
            logger.warning(
                "Outbox publish failed: id=%s type=%s attempt=%s",
                event.id, event.event_type, event.attempts, exc_info=exc,
            )

        released = self._repository.mark_failed_attempt(
            event.id,
            event.attempts,
            OutboxEventStatus.FAILED if exhausted else OutboxEventStatus.PENDING,
            (str(exc) or type(exc).__name__)[:MAX_ERROR_LENGTH],
        )
        if not released:
            logger.warning(
                "Outbox claim expired before its failure was recorded, left to the current owner: id=%s attempt=%s",
                event.id, event.attempts,
            )

    def _delete_processed(self) -> int:
        return self._repository.delete_processed_before(datetime.now(timezone.utc) - self._processed_retention)
