from post.command.post_commands import LinkPostCollabCommand
from post.usecase.link_post_collab_usecase import LinkPostCollabUsecase
from rabbitmq.event.post.post_events import PostCollabLinkedEvent


class PostCollabLinkedEventHandler:
    def __init__(self, link_post_collab_usecase: LinkPostCollabUsecase):
        self.link_post_collab_usecase = link_post_collab_usecase

    async def handle(self, event: PostCollabLinkedEvent) -> str:
        command = LinkPostCollabCommand(
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
        self.link_post_collab_usecase.execute(command)
        return event.__class__.__name__
