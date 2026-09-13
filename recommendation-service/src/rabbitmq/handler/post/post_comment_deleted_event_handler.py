from post.command.post_commands import DeletePostCommentCommand
from post.usecase.delete_post_comment_usecase import DeletePostCommentUsecase
from rabbitmq.event.post.post_events import PostCommentDeletedEvent


class PostCommentDeletedEventHandler:
    def __init__(self, delete_post_comment_usecase: DeletePostCommentUsecase):
        self.delete_post_comment_usecase = delete_post_comment_usecase

    async def handle(self, event: PostCommentDeletedEvent) -> str:
        command = DeletePostCommentCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
            comment_id=event.commentId,
            post_id=event.postId,
            user_id=event.userId,
        )
        self.delete_post_comment_usecase.execute(command)
        return event.__class__.__name__
