from enum import StrEnum


class OutboxEventStatus(StrEnum):
    """Lifecycle of an outbox row: PENDING -> PROCESSING -> PROCESSED, or
    FAILED once its publish attempts are exhausted."""

    PENDING = "PENDING"
    PROCESSING = "PROCESSING"
    PROCESSED = "PROCESSED"
    FAILED = "FAILED"
