import logging
from collections.abc import Callable
from uuid import UUID

logger = logging.getLogger(__name__)

# In-process notification of outbox events whose transaction has committed.
# OutboxRelayWorker listens while it runs and publishes each one right away
# (the low-latency path); its periodic batch still covers anything missed.
_listeners: list[Callable[[UUID], None]] = []


def add_committed_listener(listener: Callable[[UUID], None]) -> None:
    _listeners.append(listener)


def remove_committed_listener(listener: Callable[[UUID], None]) -> None:
    if listener in _listeners:
        _listeners.remove(listener)


def notify_committed(event_id: UUID) -> None:
    """Called once the transaction that stored event_id has committed."""
    for listener in list(_listeners):
        try:
            listener(event_id)
        except Exception:
            logger.exception("Committed outbox event listener failed: id=%s", event_id)
