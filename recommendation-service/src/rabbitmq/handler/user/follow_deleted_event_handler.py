from follow.command.follow_commands import DeleteFollowCommand
from follow.usecase.delete_follow_usecase import DeleteFollowUsecase
from rabbitmq.event.user.user_events import UserFollowDeletedEvent


class FollowDeletedEventHandler:
    @staticmethod
    async def handle(event: UserFollowDeletedEvent) -> str:
        command = DeleteFollowCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
            follower_user_id=event.followerUserId,
            followed_user_id=event.followedUserId,
        )
        DeleteFollowUsecase.execute(command)
        return event.__class__.__name__
