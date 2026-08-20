from post.command.post_commands import DeletePostCommentLikeCommand
from post.usecase.delete_post_comment_like_usecase import DeletePostCommentLikeUsecase
from rabbitmq.event.post.post_events import PostCommentLikeDeletedEvent


class PostCommentLikeDeletedEventHandler:
    @staticmethod
    async def handle(event: PostCommentLikeDeletedEvent) -> str:
        command = DeletePostCommentLikeCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
        )
        DeletePostCommentLikeUsecase.execute(command)
        return event.__class__.__name__
