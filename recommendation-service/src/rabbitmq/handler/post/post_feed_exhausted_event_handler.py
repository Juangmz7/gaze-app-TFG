from post.command.post_commands import ExhaustPostFeedCommand
from post.usecase.exhaust_post_feed_usecase import ExhaustPostFeedUsecase
from rabbitmq.event.post.post_events import PostFeedExhaustedEvent


class PostFeedExhaustedEventHandler:
    async def handle(event: PostFeedExhaustedEvent) -> str:
        command = ExhaustPostFeedCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
        )
        ExhaustPostFeedUsecase.execute(command)
        return event.__class__.__name__
