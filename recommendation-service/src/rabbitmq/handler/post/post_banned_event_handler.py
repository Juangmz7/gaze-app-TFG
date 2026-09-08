from post.command.post_commands import BanPostCommand
from post.usecase.ban_post_usecase import BanPostUsecase
from rabbitmq.event.post.post_events import PostBannedEvent


class PostBannedEventHandler:
    def __init__(self, ban_post_usecase: BanPostUsecase):
        self.ban_post_usecase = ban_post_usecase

    async def handle(self, event: PostBannedEvent) -> str:
        command = BanPostCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
        )
        self.ban_post_usecase.execute(command)
        return event.__class__.__name__
