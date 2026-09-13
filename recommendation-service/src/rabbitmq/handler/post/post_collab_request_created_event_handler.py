from post.command.post_commands import CreatePostCollabRequestCommand
from post.usecase.create_post_collab_request_usecase import CreatePostCollabRequestUsecase
from rabbitmq.event.post.post_events import PostCollabRequestCreatedEvent


class PostCollabRequestCreatedEventHandler:
    def __init__(self, create_post_collab_request_usecase: CreatePostCollabRequestUsecase):
        self.create_post_collab_request_usecase = create_post_collab_request_usecase

    async def handle(self, event: PostCollabRequestCreatedEvent) -> str:
        command = CreatePostCollabRequestCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
            collab_id=event.collabId,
            user_id=event.userId,
        )
        self.create_post_collab_request_usecase.execute(command)
        return event.__class__.__name__
