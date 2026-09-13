from post.command.post_commands import CreatePostCommentLikeCommand
from post.usecase.create_post_comment_like_usecase import CreatePostCommentLikeUsecase
from rabbitmq.event.post.post_events import PostCommentLikeCreatedEvent


class PostCommentLikeCreatedEventHandler:
    def __init__(self, create_post_comment_like_usecase: CreatePostCommentLikeUsecase):
        self.create_post_comment_like_usecase = create_post_comment_like_usecase

    async def handle(self, event: PostCommentLikeCreatedEvent) -> str:
        command = CreatePostCommentLikeCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
            post_id=event.postId,
            comment_id=event.commentId,
            user_id=event.userId,
            source=event.source,
            feed_position=event.feedPosition,
        )
        self.create_post_comment_like_usecase.execute(command)
        return event.__class__.__name__
