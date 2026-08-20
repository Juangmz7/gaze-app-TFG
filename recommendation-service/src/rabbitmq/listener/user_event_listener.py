import logging

from faststream.rabbit import RabbitMessage

from block.handlers.block_handlers import BlockCreatedHandler, BlockDeletedHandler
from follow.handlers.follow_handlers import FollowCreatedHandler, FollowDeletedHandler
from pipeline.handlers.user_handlers import (
    UserDeletedHandler,
    UserRegisteredHandler,
    UserUpdatedHandler,
)
from rabbitmq.config.constants import (
    UserRoutingKey,
)
from rabbitmq.exception.exceptions import RejectAndDontRequeueError
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
        processed_events_repository: ProcessedEventsRepository,
        follow_deleted_handler: FollowDeletedHandler,
        follow_created_handler: FollowCreatedHandler,
        block_deleted_handler: BlockDeletedHandler,
        user_deleted_handler: UserDeletedHandler,
        block_created_handler: BlockCreatedHandler,
        user_registered_handler: UserRegisteredHandler,
        user_updated_handler: UserUpdatedHandler,
    ):
        self.processed_events_repository = processed_events_repository
        self.follow_deleted_handler = follow_deleted_handler
        self.follow_created_handler = follow_created_handler
        self.block_deleted_handler = block_deleted_handler
        self.user_deleted_handler = user_deleted_handler
        self.block_created_handler = block_created_handler
        self.user_registered_handler = user_registered_handler
        self.user_updated_handler = user_updated_handler

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
                return await self.follow_deleted_handler.handle(
                    UserFollowDeletedEvent.model_validate(event)
                )

            case UserRoutingKey.FOLLOW_CREATED:
                return await self.follow_created_handler.handle(
                    UserFollowCreatedEvent.model_validate(event)
                )

            case UserRoutingKey.BLOCK_DELETED:
                return await self.block_deleted_handler.handle(
                    UserBlockDeletedEvent.model_validate(event)
                )

            case UserRoutingKey.DELETED:
                return await self.user_deleted_handler.handle(
                    UserDeletedEvent.model_validate(event)
                )

            case UserRoutingKey.BLOCK_CREATED:
                return await self.block_created_handler.handle(
                    UserBlockCreatedEvent.model_validate(event)
                )

            case UserRoutingKey.REGISTERED:
                return await self.user_registered_handler.handle(
                    UserRegisteredEvent.model_validate(event)
                )

            case UserRoutingKey.UPDATED:
                return await self.user_updated_handler.handle(
                    UserUpdatedEvent.model_validate(event)
                )
