from post.command.post_commands import CreatePostCommentCommand
from post.usecase.create_post_comment_usecase import CreatePostCommentUsecase
from rabbitmq.event.post.post_events import PostCommentCreatedEvent


class PostCommentCreatedEventHandler:
    async def handle(event: PostCommentCreatedEvent) -> str:
        command = CreatePostCommentCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
        )
        CreatePostCommentUsecase.execute(command)
        return event.__class__.__name__
