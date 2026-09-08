from follow.command.follow_commands import CreateFollowCommand
from follow.service.follow_service import FollowService


class CreateFollowUsecase:
    def __init__(self, follow_service: FollowService):
        self.follow_service = follow_service

    def execute(self, command: CreateFollowCommand) -> None:
        self.follow_service.create_follow(
            follower_user_id=command.follower_user_id,
            followed_user_id=command.followed_user_id,
            created_at=command.occurred_at,
        )
