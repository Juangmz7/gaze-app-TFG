from rabbitmq.event.feed.feed_events import DomainEvent
from shared.model.outbox_event import OutboxEvent
from shared.repository.outbox_event_repository import OutboxEventRepository


class OutboxPublisher:
    """Single write point of the transactional outbox. Call add() inside the
    transaction_manager.transaction() that writes the related state, so the
    event commits or rolls back with it; OutboxRelayWorker publishes it
    afterwards."""

    def __init__(self, outbox_event_repository: OutboxEventRepository):
        self.outbox_event_repository = outbox_event_repository

    def add(
        self,
        event: DomainEvent,
        *,
        event_type: str,
        exchange: str,
        routing_key: str,
    ) -> None:
        self.outbox_event_repository.add(
            OutboxEvent(
                id=event.id,
                correlation_id=event.correlationId,
                event_type=event_type,
                exchange=exchange,
                routing_key=routing_key,
                payload=event.model_dump(mode="json"),
            )
        )
