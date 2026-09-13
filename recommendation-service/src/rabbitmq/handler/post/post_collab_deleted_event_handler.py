from post.command.post_commands import DeletePostCollabCommand
from post.usecase.delete_post_collab_usecase import DeletePostCollabUsecase
from rabbitmq.event.post.post_events import PostCollabDeletedEvent


class PostCollabDeletedEventHandler:
    def __init__(self, delete_post_collab_usecase: DeletePostCollabUsecase):
        self.delete_post_collab_usecase = delete_post_collab_usecase

    async def handle(self, event: PostCollabDeletedEvent) -> str:
        command = DeletePostCollabCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
            collab_id=event.collabId,
            actioned_by=event.actionedBy,
        )
        self.delete_post_collab_usecase.execute(command)
        return event.__class__.__name__
