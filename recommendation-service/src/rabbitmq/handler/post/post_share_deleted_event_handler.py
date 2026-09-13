from post.command.post_commands import DeletePostShareCommand
from post.usecase.delete_post_share_usecase import DeletePostShareUsecase
from rabbitmq.event.post.post_events import PostShareDeletedEvent


class PostShareDeletedEventHandler:
    def __init__(self, delete_post_share_usecase: DeletePostShareUsecase):
        self.delete_post_share_usecase = delete_post_share_usecase

    async def handle(self, event: PostShareDeletedEvent) -> str:
        command = DeletePostShareCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
            post_id=event.postId,
            user_id=event.userId,
        )
        self.delete_post_share_usecase.execute(command)
        return event.__class__.__name__
