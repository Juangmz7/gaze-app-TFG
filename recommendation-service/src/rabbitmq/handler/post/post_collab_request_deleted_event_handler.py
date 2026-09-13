from post.command.post_commands import DeletePostCollabRequestCommand
from post.usecase.delete_post_collab_request_usecase import DeletePostCollabRequestUsecase
from rabbitmq.event.post.post_events import PostCollabRequestDeletedEvent


class PostCollabRequestDeletedEventHandler:
    def __init__(self, delete_post_collab_request_usecase: DeletePostCollabRequestUsecase):
        self.delete_post_collab_request_usecase = delete_post_collab_request_usecase

    async def handle(self, event: PostCollabRequestDeletedEvent) -> str:
        command = DeletePostCollabRequestCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
            collab_id=event.collabId,
            user_id=event.userId,
        )
        self.delete_post_collab_request_usecase.execute(command)
        return event.__class__.__name__
