import logging

from faststream.rabbit import RabbitMessage

from block.service.block_service import BlockService
from follow.service.follow_service import FollowService
from rabbitmq.config.constants import (
    PostRoutingKey,
    UserRoutingKey,
)
from rabbitmq.exception.exceptions import RejectAndDontRequeueError
from rabbitmq.model.event_message import EventMessage
from shared.repository.processed_events_repository import ProcessedEventsRepository
from src.rabbitmq.event.user.user_events import (
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
        block_service: BlockService,
        follow_service: FollowService,
    ):
            self.processed_events_repository = processed_events_repository
            self.block_service = block_service
            self.follow_service = follow_service

    async def handle_event(
        self,
        event: EventMessage,
        message: RabbitMessage,
    ) -> None:
        raw_routing_key = message.raw_message.routing_key
        logger.info("User event received: routing_key=%s, event_id=%s, correlation_id=%s",
                     raw_routing_key, event.id, event.correlationId)
        try:
            routing_key = PostRoutingKey(raw_routing_key)
        except ValueError as exc:
            raise RejectAndDontRequeueError(
                f"Unsupported post routing key: {raw_routing_key}"
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


    async def _match_routing_key_handler(self, routing_key: PostRoutingKey, event: EventMessage) -> str:
        match routing_key:
            case UserRoutingKey.FOLLOW_DELETED:
                return await self.handle_follow_deleted(
                    UserFollowDeletedEvent.model_validate(event)
                )

            case UserRoutingKey.FOLLOW_CREATED:
                return await self.handle_follow_created(
                    UserFollowCreatedEvent.model_validate(event)
                )

            case UserRoutingKey.BLOCK_DELETED:
                return await self.handle_block_deleted(
                    UserBlockDeletedEvent.model_validate(event)
                )

            case UserRoutingKey.DELETED:
                return await self.handle_deleted(
                    UserDeletedEvent.model_validate(event)
                )

            case UserRoutingKey.BLOCK_CREATED:
                return await self.handle_block_created(
                    UserBlockCreatedEvent.model_validate(event)
                )

            case UserRoutingKey.REGISTERED:
                return await self.handle_registered(
                    UserRegisteredEvent.model_validate(event)
                )

            case UserRoutingKey.UPDATED:
                return await self.handle_updated(
                    UserUpdatedEvent.model_validate(event)
                )

    async def handle_follow_deleted(
        self,
        event: UserFollowDeletedEvent,
    ) -> str:
        ...

    async def handle_follow_created(
        self,
        event: UserFollowCreatedEvent,
    ) -> str:
        ...

    async def handle_block_deleted(
        self,
        event: UserBlockDeletedEvent,
    ) -> str:
        ...

    async def handle_deleted(
        self,
        event: UserDeletedEvent,
    ) -> str:
        ...

    async def handle_block_created(
        self,
        event: UserBlockCreatedEvent,
    ) -> str:
        ...

    async def handle_registered(
        self,
        event: UserRegisteredEvent,
    ) -> str:
        ...

    async def handle_updated(
        self,
        event: UserUpdatedEvent,
    ) -> str:
        ...