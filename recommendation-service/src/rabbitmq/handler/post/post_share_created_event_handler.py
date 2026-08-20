from post.command.post_commands import CreatePostShareCommand
from post.usecase.create_post_share_usecase import CreatePostShareUsecase
from rabbitmq.event.post.post_events import PostShareCreatedEvent


class PostShareCreatedEventHandler:
    @staticmethod
    async def handle(event: PostShareCreatedEvent) -> str:
        command = CreatePostShareCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
        )
        CreatePostShareUsecase.execute(command)
        return event.__class__.__name__
