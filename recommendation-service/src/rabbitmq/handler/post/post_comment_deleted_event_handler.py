from post.command.post_commands import DeletePostCommentCommand
from post.usecase.delete_post_comment_usecase import DeletePostCommentUsecase
from rabbitmq.event.post.post_events import PostCommentDeletedEvent


class PostCommentDeletedEventHandler:
    @staticmethod
    async def handle(event: PostCommentDeletedEvent) -> str:
        command = DeletePostCommentCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
        )
        DeletePostCommentUsecase.execute(command)
        return event.__class__.__name__
