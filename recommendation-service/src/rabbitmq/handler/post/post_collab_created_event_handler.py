from post.command.post_commands import CreatePostCollabCommand
from post.usecase.create_post_collab_usecase import CreatePostCollabUsecase
from rabbitmq.event.post.post_events import PostCollabCreatedEvent


class PostCollabCreatedEventHandler:
    async def handle(event: PostCollabCreatedEvent) -> str:
        command = CreatePostCollabCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
            collab_id=event.collabId,
            post_id=event.postId,
            user_id=event.userId,
            created_by=event.createdBy,
        )
        CreatePostCollabUsecase.execute(command)
        return event.__class__.__name__
