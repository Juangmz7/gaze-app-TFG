from post.command.post_commands import DeletePostShareCommand
from post.usecase.delete_post_share_usecase import DeletePostShareUsecase
from rabbitmq.event.post.post_events import PostShareDeletedEvent


class PostShareDeletedEventHandler:
    @staticmethod
    async def handle(event: PostShareDeletedEvent) -> str:
        command = DeletePostShareCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
        )
        DeletePostShareUsecase.execute(command)
        return event.__class__.__name__
