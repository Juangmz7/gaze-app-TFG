import logging

from faststream.rabbit import RabbitMessage

from rabbitmq.config.constants import (
    UserRoutingKey,
)
from rabbitmq.exception.exceptions import RejectAndDontRequeueError
from rabbitmq.handler.user.block_created_event_handler import BlockCreatedEventHandler
from rabbitmq.handler.user.block_deleted_event_handler import BlockDeletedEventHandler
from rabbitmq.handler.user.follow_created_event_handler import FollowCreatedEventHandler
from rabbitmq.handler.user.follow_deleted_event_handler import FollowDeletedEventHandler
from rabbitmq.handler.user.user_deleted_event_handler import UserDeletedEventHandler
from rabbitmq.handler.user.user_registered_event_handler import UserRegisteredEventHandler
from rabbitmq.handler.user.user_updated_event_handler import UserUpdatedEventHandler
from rabbitmq.model.event_message import EventMessage
from shared.repository.processed_events_repository import ProcessedEventsRepository
from rabbitmq.event.user.user_events import (
    UserBlockCreatedEvent,
    UserBlockDeletedEvent,
    UserDeletedEvent,
    UserFollowCreatedEvent,
    UserFollowDeletedEvent,
    UserRegisteredEvent,
    UserUpdatedEvent,
)

logger = logging.getLogger(__name__)

class UserEventListener:

    def __init__(
        self,
        processed_events_repository: ProcessedEventsRepository | None = None,
    ):
        self.processed_events_repository = processed_events_repository or ProcessedEventsRepository()

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
        match routing_key:
            case UserRoutingKey.FOLLOW_DELETED:
                return await FollowDeletedEventHandler.handle(
                    UserFollowDeletedEvent.model_validate(event)
                )

            case UserRoutingKey.FOLLOW_CREATED:
                return await FollowCreatedEventHandler.handle(
                    UserFollowCreatedEvent.model_validate(event)
                )

            case UserRoutingKey.BLOCK_DELETED:
                return await BlockDeletedEventHandler.handle(
                    UserBlockDeletedEvent.model_validate(event)
                )

            case UserRoutingKey.DELETED:
                return await UserDeletedEventHandler.handle(
                    UserDeletedEvent.model_validate(event)
                )

            case UserRoutingKey.BLOCK_CREATED:
                return await BlockCreatedEventHandler.handle(
                    UserBlockCreatedEvent.model_validate(event)
                )

            case UserRoutingKey.REGISTERED:
                return await UserRegisteredEventHandler.handle(
                    UserRegisteredEvent.model_validate(event)
                )

            case UserRoutingKey.UPDATED:
                return await UserUpdatedEventHandler.handle(
                    UserUpdatedEvent.model_validate(event)
                )
