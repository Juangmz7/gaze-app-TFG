import logging

from faststream.rabbit import RabbitMessage

from rabbitmq.exception.exceptions import RejectAndDontRequeueError
from rabbitmq.handler.post.post_banned_event_handler import PostBannedEventHandler
from rabbitmq.handler.post.post_collab_request_created_event_handler import (
    PostCollabRequestCreatedEventHandler,
)
from rabbitmq.handler.post.post_collab_request_deleted_event_handler import (
    PostCollabRequestDeletedEventHandler,
)
from rabbitmq.handler.post.post_collab_created_event_handler import (
    PostCollabCreatedEventHandler,
)
from rabbitmq.handler.post.post_collab_deleted_event_handler import (
    PostCollabDeletedEventHandler,
)
from rabbitmq.handler.post.post_comment_created_event_handler import PostCommentCreatedEventHandler
from rabbitmq.handler.post.post_comment_deleted_event_handler import PostCommentDeletedEventHandler
from rabbitmq.handler.post.post_comment_like_created_event_handler import (
    PostCommentLikeCreatedEventHandler,
)
from rabbitmq.handler.post.post_comment_like_deleted_event_handler import (
    PostCommentLikeDeletedEventHandler,
)
from rabbitmq.handler.post.post_created_event_handler import PostCreatedEventHandler
from rabbitmq.handler.post.post_deleted_event_handler import PostDeletedEventHandler
from rabbitmq.handler.post.post_feed_exhausted_event_handler import PostFeedExhaustedEventHandler
from rabbitmq.handler.post.post_like_created_event_handler import PostLikeCreatedEventHandler
from rabbitmq.handler.post.post_like_deleted_event_handler import PostLikeDeletedEventHandler
from rabbitmq.handler.post.post_share_created_event_handler import PostShareCreatedEventHandler
from rabbitmq.handler.post.post_share_deleted_event_handler import PostShareDeletedEventHandler
from rabbitmq.handler.post.post_updated_event_handler import PostUpdatedEventHandler
from rabbitmq.handler.post.post_viewed_event_handler import PostViewedEventHandler
from rabbitmq.model.event_message import EventMessage
from shared.repository.processed_events_repository import ProcessedEventsRepository
from rabbitmq.config.constants import (
    PostRoutingKey,
)
from rabbitmq.event.post.post_events import (
    PostBannedEvent,
    PostCollabCreatedEvent,
    PostCollabDeletedEvent,
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

    def __init__(self, processed_events_repository: ProcessedEventsRepository | None = None):
        self.processed_events_repository = processed_events_repository or ProcessedEventsRepository()

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
        match routing_key:
            case PostRoutingKey.SHARE_DELETED:
                return await PostShareDeletedEventHandler.handle(
                    PostShareDeletedEvent.model_validate(event)
                )

            case PostRoutingKey.SHARE_CREATED:
                return await PostShareCreatedEventHandler.handle(
                    PostShareCreatedEvent.model_validate(event)
                )

            case PostRoutingKey.COLLAB_CREATED:
                return await PostCollabCreatedEventHandler.handle(
                    PostCollabCreatedEvent.model_validate(event)
                )

            case PostRoutingKey.COLLAB_DELETED:
                return await PostCollabDeletedEventHandler.handle(
                    PostCollabDeletedEvent.model_validate(event)
                )

            case PostRoutingKey.COLLAB_REQUEST_CREATED:
                return await PostCollabRequestCreatedEventHandler.handle(
                    PostCollabRequestCreatedEvent.model_validate(event)
                )

            case PostRoutingKey.COLLAB_REQUEST_DELETED:
                return await PostCollabRequestDeletedEventHandler.handle(
                    PostCollabRequestDeletedEvent.model_validate(event)
                )

            case PostRoutingKey.COMMENT_LIKE_DELETED:
                return await PostCommentLikeDeletedEventHandler.handle(
                    PostCommentLikeDeletedEvent.model_validate(event)
                )

            case PostRoutingKey.COMMENT_LIKE_CREATED:
                return await PostCommentLikeCreatedEventHandler.handle(
                    PostCommentLikeCreatedEvent.model_validate(event)
                )

            case PostRoutingKey.COMMENT_DELETED:
                return await PostCommentDeletedEventHandler.handle(
                    PostCommentDeletedEvent.model_validate(event)
                )

            case PostRoutingKey.COMMENT_CREATED:
                return await PostCommentCreatedEventHandler.handle(
                    PostCommentCreatedEvent.model_validate(event)
                )

            case PostRoutingKey.VIEWED:
                return await PostViewedEventHandler.handle(
                    PostViewedEvent.model_validate(event)
                )

            case PostRoutingKey.LIKE_DELETED:
                return await PostLikeDeletedEventHandler.handle(
                    PostLikeDeletedEvent.model_validate(event)
                )

            case PostRoutingKey.LIKE_CREATED:
                return await PostLikeCreatedEventHandler.handle(
                    PostLikeCreatedEvent.model_validate(event)
                )

            case PostRoutingKey.BANNED:
                return await PostBannedEventHandler.handle(
                    PostBannedEvent.model_validate(event)
                )

            case PostRoutingKey.DELETED:
                return await PostDeletedEventHandler.handle(
                    PostDeletedEvent.model_validate(event)
                )

            case PostRoutingKey.UPDATED:
                return await PostUpdatedEventHandler.handle(
                    PostUpdatedEvent.model_validate(event)
                )

            case PostRoutingKey.CREATED:
                return await PostCreatedEventHandler.handle(
                    PostCreatedEvent.model_validate(event)
                )

            case PostRoutingKey.FEED_EXHAUSTED:
                return await PostFeedExhaustedEventHandler.handle(
                    PostFeedExhaustedEvent.model_validate(event)
                )
