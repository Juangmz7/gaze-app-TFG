from sqlalchemy.orm import Session

from rabbitmq.event.feed.feed_events import DomainEvent
from shared.entity.outbox_event_entity import OutboxEventRecord


class OutboxPublisher:
    """Single write point of the transactional outbox. It adds the event to the
    caller's session, so the insert joins the transaction that writes the
    related state (use transaction_manager.transaction()); OutboxRelayWorker
    publishes it afterwards."""

    def add(
        self,
        session: Session,
        event: DomainEvent,
        *,
        event_type: str,
        exchange: str,
        routing_key: str,
    ) -> None:
        session.add(
            OutboxEventRecord(
                id=event.id,
                correlation_id=event.correlationId,
                event_type=event_type,
                exchange=exchange,
                routing_key=routing_key,
                payload=event.model_dump(mode="json"),
            )
        )
