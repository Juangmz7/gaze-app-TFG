from post.command.post_commands import DeletePostCommand
from post.usecase.delete_post_usecase import DeletePostUsecase
from rabbitmq.event.post.post_events import PostDeletedEvent


class PostDeletedEventHandler:
    async def handle(event: PostDeletedEvent) -> str:
        command = DeletePostCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
            post_id=event.postId,
            user_id=event.userId,
        )
        DeletePostUsecase.execute(command)
        return event.__class__.__name__
