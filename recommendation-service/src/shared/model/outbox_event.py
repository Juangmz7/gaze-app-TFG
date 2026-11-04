from dataclasses import dataclass
from typing import Any
from uuid import UUID


@dataclass(frozen=True)
class OutboxEvent:
    """An event waiting in the transactional outbox. exchange and routing_key
    are stored with it so the relay can publish any event type. attempts is the
    number of claims so far; on a claimed event it also identifies that claim."""

    id: UUID
    correlation_id: UUID
    event_type: str
    exchange: str
    routing_key: str
    payload: dict[str, Any]
    attempts: int = 0
