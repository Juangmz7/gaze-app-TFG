from post.command.post_commands import DeletePostCollabRequestCommand
from post.usecase.delete_post_collab_request_usecase import DeletePostCollabRequestUsecase
from rabbitmq.event.post.post_events import PostCollabRequestDeletedEvent


class PostCollabRequestDeletedEventHandler:
    async def handle(event: PostCollabRequestDeletedEvent) -> str:
        command = DeletePostCollabRequestCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
        )
        DeletePostCollabRequestUsecase.execute(command)
        return event.__class__.__name__
