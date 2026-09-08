from post.command.post_commands import CreatePostCommentCommand
from post.usecase.create_post_comment_usecase import CreatePostCommentUsecase
from rabbitmq.event.post.post_events import PostCommentCreatedEvent


class PostCommentCreatedEventHandler:
    def __init__(self, create_post_comment_usecase: CreatePostCommentUsecase):
        self.create_post_comment_usecase = create_post_comment_usecase

    async def handle(self, event: PostCommentCreatedEvent) -> str:
        command = CreatePostCommentCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
            comment_id=event.commentId,
            post_id=event.postId,
            user_id=event.userId,
        )
        self.create_post_comment_usecase.execute(command)
        return event.__class__.__name__
