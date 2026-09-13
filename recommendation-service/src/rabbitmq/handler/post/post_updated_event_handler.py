from post.command.post_commands import UpdatePostCommand
from post.usecase.update_post_usecase import UpdatePostUsecase
from rabbitmq.event.post.post_events import PostUpdatedEvent


class PostUpdatedEventHandler:
    def __init__(self, update_post_usecase: UpdatePostUsecase):
        self.update_post_usecase = update_post_usecase

    async def handle(self, event: PostUpdatedEvent) -> str:
        command = UpdatePostCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
            post_id=event.postId,
            user_id=event.userId,
            collab_id=event.collabId,
            description=event.description,
            tagged_users=event.taggedUsers,
            post_tags=event.postTags,
            created_at=event.createdAt,
            updated_at=event.updatedAt,
        )
        self.update_post_usecase.execute(command)
        return event.__class__.__name__
