import logging
from typing import Any

from faststream.rabbit import RabbitMessage
from pydantic import ValidationError

from observability.log_context import correlation_id_ctx, event_id_ctx
from rabbitmq.config.constants import FeedRoutingKey
from rabbitmq.event.post.post_events import PostFeedExhaustedEvent
from rabbitmq.exception.exceptions import RejectAndDontRequeueError
from rabbitmq.handler.post.post_feed_exhausted_event_handler import PostFeedExhaustedEventHandler

logger = logging.getLogger(__name__)


class FeedEventListener:
    """Unlike the post and user listeners, this one opens no transaction around
    the handler: the pipeline must run outside any write transaction (see
    ExhaustPostFeedUsecase). It receives the raw body and validates it itself, so
    a malformed payload is rejected here, before the handler, with only its error
    types logged."""

    def __init__(self, post_feed_exhausted_event_handler: PostFeedExhaustedEventHandler):
        self.post_feed_exhausted_event_handler = post_feed_exhausted_event_handler

    async def handle_event(self, body: Any, message: RabbitMessage) -> None:
        raw_routing_key = message.raw_message.routing_key
        if raw_routing_key != FeedRoutingKey.FEED_EXHAUSTED:
            raise RejectAndDontRequeueError(f"Unsupported feed routing key: {raw_routing_key}")

        try:
            event = PostFeedExhaustedEvent.model_validate_json(body)
        except ValidationError as exc:
            # Every error type, not the raw input: it may be large or not even text
            raise RejectAndDontRequeueError(
                f"Malformed feed request: errors={[(e['loc'], e['type']) for e in exc.errors()]}"
            ) from exc

        correlation_id_ctx.set(str(event.correlationId))
        event_id_ctx.set(str(event.id))

        await self.post_feed_exhausted_event_handler.handle(event)
        logger.info(
            "Feed request processed: event_id=%s correlation_id=%s user_id=%s",
            event.id, event.correlationId, event.userId,
        )
