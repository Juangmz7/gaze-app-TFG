from follow.command.follow_commands import DeleteFollowCommand
from follow.service.follow_service import FollowService


class DeleteFollowUsecase:
    def __init__(self, follow_service: FollowService):
        self.follow_service = follow_service

    def execute(self, command: DeleteFollowCommand) -> None:
        self.follow_service.remove_follow(
            follower_user_id=command.follower_user_id,
            followed_user_id=command.followed_user_id,
        )
