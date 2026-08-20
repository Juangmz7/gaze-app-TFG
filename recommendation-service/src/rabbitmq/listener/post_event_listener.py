import logging

from faststream.rabbit import RabbitMessage

from pipeline.handlers.interaction_handlers import (
    PostCollabRequestCreatedHandler,
    PostCollabRequestDeletedHandler,
    PostCommentCreatedHandler,
    PostCommentDeletedHandler,
    PostCommentLikeCreatedHandler,
    PostCommentLikeDeletedHandler,
    PostLikeCreatedHandler,
    PostLikeDeletedHandler,
    PostShareCreatedHandler,
    PostShareDeletedHandler,
    PostViewedHandler,
)
from pipeline.handlers.post_handlers import (
    PostBannedHandler,
    PostCreatedHandler,
    PostDeletedHandler,
    PostFeedExhaustedHandler,
    PostUpdatedHandler,
)
from rabbitmq.exception.exceptions import RejectAndDontRequeueError
from rabbitmq.model.event_message import EventMessage
from shared.repository.processed_events_repository import ProcessedEventsRepository
from rabbitmq.config.constants import (
    PostRoutingKey,
)
from rabbitmq.event.post.post_events import (
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

    def __init__(
        self,
        processed_events_repository: ProcessedEventsRepository,
        share_deleted_handler: PostShareDeletedHandler,
        share_created_handler: PostShareCreatedHandler,
        collab_request_created_handler: PostCollabRequestCreatedHandler,
        collab_request_deleted_handler: PostCollabRequestDeletedHandler,
        comment_like_deleted_handler: PostCommentLikeDeletedHandler,
        comment_like_created_handler: PostCommentLikeCreatedHandler,
        comment_deleted_handler: PostCommentDeletedHandler,
        comment_created_handler: PostCommentCreatedHandler,
        viewed_handler: PostViewedHandler,
        like_deleted_handler: PostLikeDeletedHandler,
        like_created_handler: PostLikeCreatedHandler,
        banned_handler: PostBannedHandler,
        deleted_handler: PostDeletedHandler,
        updated_handler: PostUpdatedHandler,
        created_handler: PostCreatedHandler,
        feed_exhausted_handler: PostFeedExhaustedHandler,
    ):
        self.processed_events_repository = processed_events_repository
        self.share_deleted_handler = share_deleted_handler
        self.share_created_handler = share_created_handler
        self.collab_request_created_handler = collab_request_created_handler
        self.collab_request_deleted_handler = collab_request_deleted_handler
        self.comment_like_deleted_handler = comment_like_deleted_handler
        self.comment_like_created_handler = comment_like_created_handler
        self.comment_deleted_handler = comment_deleted_handler
        self.comment_created_handler = comment_created_handler
        self.viewed_handler = viewed_handler
        self.like_deleted_handler = like_deleted_handler
        self.like_created_handler = like_created_handler
        self.banned_handler = banned_handler
        self.deleted_handler = deleted_handler
        self.updated_handler = updated_handler
        self.created_handler = created_handler
        self.feed_exhausted_handler = feed_exhausted_handler

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
        match routing_key:
            case PostRoutingKey.SHARE_DELETED:
                return await self.share_deleted_handler.handle(
                    PostShareDeletedEvent.model_validate(event)
                )

            case PostRoutingKey.SHARE_CREATED:
                return await self.share_created_handler.handle(
                    PostShareCreatedEvent.model_validate(event)
                )

            case PostRoutingKey.COLLAB_REQUEST_CREATED:
                return await self.collab_request_created_handler.handle(
                    PostCollabRequestCreatedEvent.model_validate(event)
                )

            case PostRoutingKey.COLLAB_REQUEST_DELETED:
                return await self.collab_request_deleted_handler.handle(
                    PostCollabRequestDeletedEvent.model_validate(event)
                )

            case PostRoutingKey.COMMENT_LIKE_DELETED:
                return await self.comment_like_deleted_handler.handle(
                    PostCommentLikeDeletedEvent.model_validate(event)
                )

            case PostRoutingKey.COMMENT_LIKE_CREATED:
                return await self.comment_like_created_handler.handle(
                    PostCommentLikeCreatedEvent.model_validate(event)
                )

            case PostRoutingKey.COMMENT_DELETED:
                return await self.comment_deleted_handler.handle(
                    PostCommentDeletedEvent.model_validate(event)
                )

            case PostRoutingKey.COMMENT_CREATED:
                return await self.comment_created_handler.handle(
                    PostCommentCreatedEvent.model_validate(event)
                )

            case PostRoutingKey.VIEWED:
                return await self.viewed_handler.handle(
                    PostViewedEvent.model_validate(event)
                )

            case PostRoutingKey.LIKE_DELETED:
                return await self.like_deleted_handler.handle(
                    PostLikeDeletedEvent.model_validate(event)
                )

            case PostRoutingKey.LIKE_CREATED:
                return await self.like_created_handler.handle(
                    PostLikeCreatedEvent.model_validate(event)
                )

            case PostRoutingKey.BANNED:
                return await self.banned_handler.handle(
                    PostBannedEvent.model_validate(event)
                )

            case PostRoutingKey.DELETED:
                return await self.deleted_handler.handle(
                    PostDeletedEvent.model_validate(event)
                )

            case PostRoutingKey.UPDATED:
                return await self.updated_handler.handle(
                    PostUpdatedEvent.model_validate(event)
                )

            case PostRoutingKey.CREATED:
                return await self.created_handler.handle(
                    PostCreatedEvent.model_validate(event)
                )

            case PostRoutingKey.FEED_EXHAUSTED:
                return await self.feed_exhausted_handler.handle(
                    PostFeedExhaustedEvent.model_validate(event)
                )
