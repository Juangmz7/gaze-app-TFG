from post.command.post_commands import DeletePostLikeCommand
from post.usecase.delete_post_like_usecase import DeletePostLikeUsecase
from rabbitmq.event.post.post_events import PostLikeDeletedEvent


class PostLikeDeletedEventHandler:
    @staticmethod
    async def handle(event: PostLikeDeletedEvent) -> str:
        command = DeletePostLikeCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
            post_id=event.postId,
            user_id=event.userId,
            source=event.source,
            feed_position=event.feedPosition,
        )
        DeletePostLikeUsecase.execute(command)
        return event.__class__.__name__
