
from dataclasses import dataclass

from follow.model.follow import Follow
from follow.repository.follow_repository import FollowRepository

class FollowService:
    def __init__(self, follow_repository: FollowRepository):
        self.follow_repository = follow_repository

    def create_follow(self, follower_user_id: str, followed_user_id: str) -> None:
        follow = Follow(
            follower_user_id=follower_user_id,
            followed_user_id=followed_user_id
        )
        self.follow_repository.create_follow(
            follow=follow
        )

    def remove_follow(self, follower_user_id: str, followed_user_id: str) -> None:
        self.follow_repository.remove_follow(
            follower_user_id=follower_user_id,
            followed_user_id=followed_user_id
        )

    def remove_follows_between_users(self, user_id_1: str, user_id_2: str) -> None:
        self.follow_repository.remove_follows_between_users(
            user_id_1=user_id_1,
            user_id_2=user_id_2
        )