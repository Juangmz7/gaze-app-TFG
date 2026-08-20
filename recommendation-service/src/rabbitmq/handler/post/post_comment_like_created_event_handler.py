from post.command.post_commands import CreatePostCommentLikeCommand
from post.usecase.create_post_comment_like_usecase import CreatePostCommentLikeUsecase
from rabbitmq.event.post.post_events import PostCommentLikeCreatedEvent


class PostCommentLikeCreatedEventHandler:
    @staticmethod
    async def handle(event: PostCommentLikeCreatedEvent) -> str:
        command = CreatePostCommentLikeCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
        )
        CreatePostCommentLikeUsecase.execute(command)
        return event.__class__.__name__
