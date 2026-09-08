from post.command.post_commands import CreatePostShareCommand
from post.usecase.create_post_share_usecase import CreatePostShareUsecase
from rabbitmq.event.post.post_events import PostShareCreatedEvent


class PostShareCreatedEventHandler:
    def __init__(self, create_post_share_usecase: CreatePostShareUsecase):
        self.create_post_share_usecase = create_post_share_usecase

    async def handle(self, event: PostShareCreatedEvent) -> str:
        command = CreatePostShareCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
            post_id=event.postId,
            user_id=event.userId,
        )
        self.create_post_share_usecase.execute(command)
        return event.__class__.__name__
