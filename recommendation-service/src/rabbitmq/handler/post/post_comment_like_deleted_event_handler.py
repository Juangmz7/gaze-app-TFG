from post.command.post_commands import DeletePostCommentLikeCommand
from post.usecase.delete_post_comment_like_usecase import DeletePostCommentLikeUsecase
from rabbitmq.event.post.post_events import PostCommentLikeDeletedEvent


class PostCommentLikeDeletedEventHandler:
    def __init__(self, delete_post_comment_like_usecase: DeletePostCommentLikeUsecase):
        self.delete_post_comment_like_usecase = delete_post_comment_like_usecase

    async def handle(self, event: PostCommentLikeDeletedEvent) -> str:
        command = DeletePostCommentLikeCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
            comment_id=event.commentId,
            user_id=event.userId,
            source=event.source,
            feed_position=event.feedPosition,
        )
        self.delete_post_comment_like_usecase.execute(command)
        return event.__class__.__name__
