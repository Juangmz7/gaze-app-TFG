from post.command.post_commands import UpdatePostCommand
from post.usecase.update_post_usecase import UpdatePostUsecase
from rabbitmq.event.post.post_events import PostUpdatedEvent


class PostUpdatedEventHandler:
    @staticmethod
    async def handle(event: PostUpdatedEvent) -> str:
        command = UpdatePostCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
            post_id=event.postId,
            user_id=event.userId,
            description=event.description,
            tagged_users=event.taggedUsers,
            post_tags=event.postTags,
            created_at=event.createdAt,
            updated_at=event.updatedAt,
        )
        UpdatePostUsecase.execute(command)
        return event.__class__.__name__
