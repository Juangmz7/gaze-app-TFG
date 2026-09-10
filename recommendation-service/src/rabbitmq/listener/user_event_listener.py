import logging
from contextlib import nullcontext
from typing import Any, Mapping

from faststream.rabbit import RabbitMessage

from rabbitmq.config.constants import (
    UserRoutingKey,
)
from rabbitmq.exception.exceptions import RejectAndDontRequeueError
from rabbitmq.model.event_message import EventMessage
from shared.repository.processed_events_repository import ProcessedEventsRepository

logger = logging.getLogger(__name__)

class UserEventListener:

    def __init__(
        self,
        processed_events_repository: ProcessedEventsRepository,
        handlers: Mapping[UserRoutingKey, tuple[type[EventMessage], Any]],
        transaction_manager: Any | None = None,
    ):
        self.processed_events_repository = processed_events_repository
        self.handlers = handlers
        self.transaction_manager = transaction_manager

    async def handle_event(
        self,
        event: EventMessage,
        message: RabbitMessage,
    ) -> None:
        raw_routing_key = message.raw_message.routing_key
        logger.info("User event received: routing_key=%s, event_id=%s, correlation_id=%s",
                     raw_routing_key, event.id, event.correlationId)
        try:
            routing_key = UserRoutingKey(raw_routing_key)
        except ValueError as exc:
            raise RejectAndDontRequeueError(
                f"Unsupported user routing key: {raw_routing_key}"
            ) from exc

        transaction = (
            self.transaction_manager.transaction()
            if self.transaction_manager is not None
            else nullcontext()
        )
        with transaction:
            if self.processed_events_repository.isAlreadyProcessed(
                correlation_id=event.correlationId,
                event_id=event.id,
            ):
                logger.warning("Duplicate user event detected, discarding: event_id=%s, correlation_id=%s",
                                event.id, event.correlationId)
                return

            logger.debug("Dispatching to handler for routing_key=%s", raw_routing_key)
            event_name = await self._match_routing_key_handler(routing_key, event)

            self.processed_events_repository.setEventAsProcessed(
                correlation_id=event.correlationId,
                event_id=event.id,
                event_name=event_name,
            )
        logger.info("User event processed successfully: event_name=%s, event_id=%s, correlation_id=%s",
                      event_name, event.id, event.correlationId)


    async def _match_routing_key_handler(self, routing_key: UserRoutingKey, event: EventMessage) -> str:
        event_model, handler = self.handlers[routing_key]
        return await handler.handle(event_model.model_validate(event))
