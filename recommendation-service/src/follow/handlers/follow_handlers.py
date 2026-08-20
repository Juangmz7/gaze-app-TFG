from follow.commands import CreateFollowCommand, DeleteFollowCommand
from follow.usecase import CreateFollowUsecase, DeleteFollowUsecase
from rabbitmq.event.user.user_events import (
    UserFollowCreatedEvent,
    UserFollowDeletedEvent,
)


class FollowCreatedHandler:
    def __init__(self, create_follow_usecase: CreateFollowUsecase):
        self.create_follow_usecase = create_follow_usecase

    async def handle(self, event: UserFollowCreatedEvent) -> str:
        command = CreateFollowCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
            follower_user_id=event.followerUserId,
            followed_user_id=event.followedUserId,
        )
        self.create_follow_usecase.execute(command)
        return event.__class__.__name__


class FollowDeletedHandler:
    def __init__(self, delete_follow_usecase: DeleteFollowUsecase):
        self.delete_follow_usecase = delete_follow_usecase

    async def handle(self, event: UserFollowDeletedEvent) -> str:
        command = DeleteFollowCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
            follower_user_id=event.followerUserId,
            followed_user_id=event.followedUserId,
        )
        self.delete_follow_usecase.execute(command)
        return event.__class__.__name__
