from post.command.post_commands import CreatePostLikeCommand
from post.usecase.create_post_like_usecase import CreatePostLikeUsecase
from rabbitmq.event.post.post_events import PostLikeCreatedEvent


class PostLikeCreatedEventHandler:
    async def handle(event: PostLikeCreatedEvent) -> str:
        command = CreatePostLikeCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
            post_id=event.postId,
            user_id=event.userId,
            source=event.source,
            feed_position=event.feedPosition,
            created_at=event.createdAt,
        )
        CreatePostLikeUsecase.execute(command)
        return event.__class__.__name__
