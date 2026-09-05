from post.command.post_commands import CreatePostCommentLikeCommand
from post.usecase.create_post_comment_like_usecase import CreatePostCommentLikeUsecase
from rabbitmq.event.post.post_events import PostCommentLikeCreatedEvent


class PostCommentLikeCreatedEventHandler:
    async def handle(event: PostCommentLikeCreatedEvent) -> str:
        command = CreatePostCommentLikeCommand(
            occurred_at=event.occurredAt,
            post_id=event.postId,
            user_id=event.userId,
            source=event.source,
            feed_position=event.feedPosition,
            created_at=event.createdAt,
        )
        CreatePostCommentLikeUsecase.execute(command)
        return event.__class__.__name__
