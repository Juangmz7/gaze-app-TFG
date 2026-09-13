from post.command.post_commands import CreatePostCommand
from post.usecase.create_post_usecase import CreatePostUsecase
from rabbitmq.event.post.post_events import PostCreatedEvent


class PostCreatedEventHandler:
    def __init__(self, create_post_usecase: CreatePostUsecase):
        self.create_post_usecase = create_post_usecase

    async def handle(self, event: PostCreatedEvent) -> str:
        command = CreatePostCommand(
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
        self.create_post_usecase.execute(command)
        return event.__class__.__name__
