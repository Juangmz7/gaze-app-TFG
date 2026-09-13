from post.command.post_commands import CreatePostCollabForExistingPostCommand
from post.usecase.create_post_collab_for_existing_post_usecase import (
    CreatePostCollabForExistingPostUsecase,
)
from rabbitmq.event.post.post_events import PostCollabOpenedForExistingPostEvent


class PostCollabOpenedForExistingPostEventHandler:
    def __init__(
            self,
            create_post_collab_for_existing_post_usecase: CreatePostCollabForExistingPostUsecase,
    ):
        self.create_post_collab_for_existing_post_usecase = (
            create_post_collab_for_existing_post_usecase
        )

    async def handle(self, event: PostCollabOpenedForExistingPostEvent) -> str:
        command = CreatePostCollabForExistingPostCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
            collab_id=event.collabId,
            title=event.title,
            collab_status=event.collabStatus,
            post_id=event.postId,
            user_id=event.userId,
            created_by=event.createdBy,
            collab_created_at=event.collabCreatedAt,
            description=event.description,
            tagged_users=event.taggedUsers,
            post_tags=event.postTags,
            post_created_at=event.postCreatedAt,
            post_updated_at=event.postUpdatedAt,
        )
        self.create_post_collab_for_existing_post_usecase.execute(command)
        return event.__class__.__name__
