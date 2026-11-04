from abc import ABC, abstractmethod
from datetime import datetime
from uuid import UUID

from shared.enum.outbox_event_status import OutboxEventStatus
from shared.model.outbox_event import OutboxEvent


class OutboxEventRepository(ABC):
    """Persistence of the transactional outbox. add() joins the caller's
    transaction; the relay operations each run in their own short one."""

    @abstractmethod
    def add(self, event: OutboxEvent) -> None:
        """Stores a PENDING event in the current transaction, so it commits or
        rolls back together with the state change that produced it."""

    @abstractmethod
    def claim_next(self, locked_before: datetime) -> OutboxEvent | None:
        """Atomically claims the oldest PENDING event, or a PROCESSING one whose
        lock is older than locked_before (its relay died mid-publish): marks it
        PROCESSING, locks it and increments attempts. Concurrent relays never
        claim the same event. Returns None when nothing is claimable."""

    @abstractmethod
    def mark_processed(self, event_id: UUID) -> None:
        """Marks a confirmed event PROCESSED, whoever reports it."""

    @abstractmethod
    def mark_failed_attempt(
        self, event_id: UUID, attempts: int, status: OutboxEventStatus, last_error: str
    ) -> bool:
        """Releases a failed claim with status (PENDING to retry, FAILED once
        exhausted). Only the claim identified by attempts may do it: returns
        False, changing nothing, when the claim expired and the event was
        reclaimed or already processed by another relay."""

    @abstractmethod
    def delete_processed_before(self, processed_before: datetime) -> int:
        """Deletes PROCESSED events older than processed_before; returns how many."""
