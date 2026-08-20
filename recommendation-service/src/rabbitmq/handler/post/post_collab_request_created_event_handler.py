from post.command.post_commands import CreatePostCollabRequestCommand
from post.usecase.create_post_collab_request_usecase import CreatePostCollabRequestUsecase
from rabbitmq.event.post.post_events import PostCollabRequestCreatedEvent


class PostCollabRequestCreatedEventHandler:
    async def handle(event: PostCollabRequestCreatedEvent) -> str:
        command = CreatePostCollabRequestCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
        )
        CreatePostCollabRequestUsecase.execute(command)
        return event.__class__.__name__
