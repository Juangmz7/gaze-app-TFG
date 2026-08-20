import logging

from faststream.rabbit import RabbitMessage

from rabbitmq.exception.exceptions import RejectAndDontRequeueError
from rabbitmq.model.event_message import EventMessage
from shared.repository.processed_events_repository import ProcessedEventsRepository
from src.rabbitmq.config.constants import (
    PostRoutingKey,
    UserRoutingKey,
)
from src.rabbitmq.event.post.post_events import (
    PostBannedEvent,
    PostCollabRequestCreatedEvent,
    PostCollabRequestDeletedEvent,
    PostCommentCreatedEvent,
    PostCommentDeletedEvent,
    PostCommentLikeCreatedEvent,
    PostCommentLikeDeletedEvent,
    PostCreatedEvent,
    PostDeletedEvent,
    PostFeedExhaustedEvent,
    PostLikeCreatedEvent,
    PostLikeDeletedEvent,
    PostShareCreatedEvent,
    PostShareDeletedEvent,
    PostUpdatedEvent,
    PostViewedEvent,
)

logger = logging.getLogger(__name__)

class PostEventListener:

    def __init__(self, processed_events_repository: ProcessedEventsRepository):
        self.processed_events_repository = processed_events_repository

    async def handle_event(
        self,
        event: EventMessage,
        message: RabbitMessage,
    ) -> None:
        raw_routing_key = message.raw_message.routing_key
        logger.info("Post event received: routing_key=%s, event_id=%s, correlation_id=%s",
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
        match routing_key:
                    case PostRoutingKey.SHARE_DELETED:
                        return await self.handle_share_deleted(
                            PostShareDeletedEvent.model_validate(event)
                        )
        
                    case PostRoutingKey.SHARE_CREATED:
                        return await self.handle_share_created(
                            PostShareCreatedEvent.model_validate(event)
                        )
        
                    case PostRoutingKey.COLLAB_REQUEST_CREATED:
                        return await self.handle_collab_request_created(
                            PostCollabRequestCreatedEvent.model_validate(event)
                        )
        
                    case PostRoutingKey.COLLAB_REQUEST_DELETED:
                        return await self.handle_collab_request_deleted(
                            PostCollabRequestDeletedEvent.model_validate(event)
                        )
        
                    case PostRoutingKey.COMMENT_LIKE_DELETED:
                        return await self.handle_comment_like_deleted(
                            PostCommentLikeDeletedEvent.model_validate(event)
                        )
        
                    case PostRoutingKey.COMMENT_LIKE_CREATED:
                        return await self.handle_comment_like_created(
                            PostCommentLikeCreatedEvent.model_validate(event)
                        )
        
                    case PostRoutingKey.COMMENT_DELETED:
                        return await self.handle_comment_deleted(
                            PostCommentDeletedEvent.model_validate(event)
                        )
        
                    case PostRoutingKey.COMMENT_CREATED:
                        return await self.handle_comment_created(
                            PostCommentCreatedEvent.model_validate(event)
                        )
        
                    case PostRoutingKey.VIEWED:
                        return await self.handle_viewed(
                            PostViewedEvent.model_validate(event)
                        )
        
                    case PostRoutingKey.LIKE_DELETED:
                        return await self.handle_like_deleted(
                            PostLikeDeletedEvent.model_validate(event)
                        )
        
                    case PostRoutingKey.LIKE_CREATED:
                        return await self.handle_like_created(
                            PostLikeCreatedEvent.model_validate(event)
                        )
        
                    case PostRoutingKey.BANNED:
                        return await self.handle_banned(
                            PostBannedEvent.model_validate(event)
                        )
        
                    case PostRoutingKey.DELETED:
                        return await self.handle_deleted(
                            PostDeletedEvent.model_validate(event)
                        )
        
                    case PostRoutingKey.UPDATED:
                        return await self.handle_updated(
                            PostUpdatedEvent.model_validate(event)
                        )
        
                    case PostRoutingKey.CREATED:
                        return await self.handle_created(
                            PostCreatedEvent.model_validate(event)
                        )
        
                    case PostRoutingKey.FEED_EXHAUSTED:
                        return await self.handle_feed_exhausted(
                            PostFeedExhaustedEvent.model_validate(event)
                        )

    async def handle_share_deleted(
        self,
        event: PostShareDeletedEvent,
    ) -> str:
        ...

    async def handle_share_created(
        self,
        event: PostShareCreatedEvent,
    ) -> str:
        ...

    async def handle_collab_request_created(
        self,
        event: PostCollabRequestCreatedEvent,
    ) -> str:
        ...

    async def handle_collab_request_deleted(
        self,
        event: PostCollabRequestDeletedEvent,
    ) -> str:
        ...

    async def handle_comment_like_deleted(
        self,
        event: PostCommentLikeDeletedEvent,
    ) -> str:
        ...

    async def handle_comment_like_created(
        self,
        event: PostCommentLikeCreatedEvent,
    ) -> str:
        ...

    async def handle_comment_deleted(
        self,
        event: PostCommentDeletedEvent,
    ) -> str:
        ...

    async def handle_comment_created(
        self,
        event: PostCommentCreatedEvent,
    ) -> str:
        ...

    async def handle_viewed(
        self,
        event: PostViewedEvent,
    ) -> str:
        ...

    async def handle_like_deleted(
        self,
        event: PostLikeDeletedEvent,
    ) -> str:
        ...

    async def handle_like_created(
        self,
        event: PostLikeCreatedEvent,
    ) -> str:
        ...

    async def handle_banned(
        self,
        event: PostBannedEvent,
    ) -> str:
        ...

    async def handle_deleted(
        self,
        event: PostDeletedEvent,
    ) -> str:
        ...

    async def handle_updated(
        self,
        event: PostUpdatedEvent,
    ) -> str:
        ...

    async def handle_created(
        self,
        event: PostCreatedEvent,
    ) -> str:
        ...

    async def handle_feed_exhausted(
        self,
        event: PostFeedExhaustedEvent,
    ) -> str:
        ...