from follow.command.follow_commands import CreateFollowCommand
from follow.usecase.create_follow_usecase import CreateFollowUsecase
from rabbitmq.event.user.user_events import UserFollowCreatedEvent


class FollowCreatedEventHandler:
    async def handle(event: UserFollowCreatedEvent) -> str:
        command = CreateFollowCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
            follower_user_id=event.followerUserId,
            followed_user_id=event.followedUserId,
        )
        CreateFollowUsecase.execute(command)
        return event.__class__.__name__
