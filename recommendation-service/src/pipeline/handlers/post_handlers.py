from pipeline.commands.post_commands import (
    BanPostCommand,
    CreatePostCommand,
    DeletePostCommand,
    FeedExhaustedCommand,
    UpdatePostCommand,
)
from pipeline.usecase.post_usecases import (
    BanPostUsecase,
    CreatePostUsecase,
    DeletePostUsecase,
    FeedExhaustedUsecase,
    UpdatePostUsecase,
)
from rabbitmq.event.post.post_events import (
    PostBannedEvent,
    PostCreatedEvent,
    PostDeletedEvent,
    PostFeedExhaustedEvent,
    PostUpdatedEvent,
)


class PostCreatedHandler:
    def __init__(self, create_post_usecase: CreatePostUsecase):
        self.create_post_usecase = create_post_usecase

    async def handle(self, event: PostCreatedEvent) -> str:
        command = CreatePostCommand(
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
        self.create_post_usecase.execute(command)
        return event.__class__.__name__


class PostUpdatedHandler:
    def __init__(self, update_post_usecase: UpdatePostUsecase):
        self.update_post_usecase = update_post_usecase

    async def handle(self, event: PostUpdatedEvent) -> str:
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
        self.update_post_usecase.execute(command)
        return event.__class__.__name__


class PostDeletedHandler:
    def __init__(self, delete_post_usecase: DeletePostUsecase):
        self.delete_post_usecase = delete_post_usecase

    async def handle(self, event: PostDeletedEvent) -> str:
        command = DeletePostCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
            post_id=event.postId,
            user_id=event.userId,
        )
        self.delete_post_usecase.execute(command)
        return event.__class__.__name__


class PostBannedHandler:
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


class PostFeedExhaustedHandler:
    def __init__(self, feed_exhausted_usecase: FeedExhaustedUsecase):
        self.feed_exhausted_usecase = feed_exhausted_usecase

    async def handle(self, event: PostFeedExhaustedEvent) -> str:
        command = FeedExhaustedCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
        )
        self.feed_exhausted_usecase.execute(command)
        return event.__class__.__name__
