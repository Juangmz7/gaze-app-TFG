from post.command.post_commands import BanPostCommand
from post.usecase.ban_post_usecase import BanPostUsecase
from rabbitmq.event.post.post_events import PostBannedEvent


class PostBannedEventHandler:
    async def handle(event: PostBannedEvent) -> str:
        command = BanPostCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
        )
        BanPostUsecase.execute(command)
        return event.__class__.__name__
