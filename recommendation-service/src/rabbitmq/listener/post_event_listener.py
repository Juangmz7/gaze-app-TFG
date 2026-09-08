import logging
from typing import Any, Mapping

from faststream.rabbit import RabbitMessage

from rabbitmq.exception.exceptions import RejectAndDontRequeueError
from rabbitmq.model.event_message import EventMessage
from shared.repository.processed_events_repository import ProcessedEventsRepository
from rabbitmq.config.constants import (
    PostRoutingKey,
)

logger = logging.getLogger(__name__)

class PostEventListener:

    def __init__(
            self,
            processed_events_repository: ProcessedEventsRepository,
            handlers: Mapping[PostRoutingKey, tuple[type[EventMessage], Any]],
    ):
        self.processed_events_repository = processed_events_repository
        self.handlers = handlers

    async def handle_event(
        self,
        event: EventMessage,
        message: RabbitMessage,
    ) -> None:
        raw_routing_key = message.raw_message.routing_key
        logger.info("Post event received: routing_key=%s, event_id=%s, correlation_id=%s",
                     raw_routing_key, event.id, event.correlationId)
        try:
            routing_key = PostRoutingKey(raw_routing_key)
        except ValueError as exc:
            logger.error("Unsupported post routing key: %s", raw_routing_key)
            raise RejectAndDontRequeueError(
                f"Unsupported post routing key: {raw_routing_key}"
            ) from exc

        if self.processed_events_repository.isAlreadyProcessed(
            correlation_id=event.correlationId,
            event_id=event.id,
        ):
            logger.warning("Duplicate post event detected, discarding: event_id=%s, correlation_id=%s",
                            event.id, event.correlationId)
            return

        logger.debug("Dispatching to handler for routing_key=%s", raw_routing_key)
        event_name = await self._match_routing_key_handler(routing_key, event)
        
        self.processed_events_repository.setEventAsProcessed(
            correlation_id=event.correlationId,
            event_id=event.id,
            event_name=event_name,
        )
        logger.info("Post event processed successfully: event_name=%s, event_id=%s, correlation_id=%s",
                      event_name, event.id, event.correlationId)

    async def _match_routing_key_handler(self, routing_key: PostRoutingKey, event: EventMessage) -> str:
        event_model, handler = self.handlers[routing_key]
        return await handler.handle(event_model.model_validate(event))
