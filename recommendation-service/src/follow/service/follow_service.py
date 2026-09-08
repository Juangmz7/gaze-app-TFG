
import logging
from datetime import datetime
from uuid import UUID

from follow.model.follow import Follow
from follow.repository.follow_repository import FollowRepository

logger = logging.getLogger(__name__)

class FollowService:
    def __init__(self, follow_repository: FollowRepository):
        self.follow_repository = follow_repository

    def create_follow(
            self,
            follower_user_id: UUID,
            followed_user_id: UUID,
            created_at: datetime,
    ) -> None:
        logger.info("Creating follow: follower=%s, followed=%s", follower_user_id, followed_user_id)
        follow = Follow(
            follower_id=follower_user_id,
            followed_id=followed_user_id,
            created_at=created_at,
        )
        self.follow_repository.create_follow(
            follow=follow
        )
        logger.info("Follow created successfully: follower=%s, followed=%s",
                      follower_user_id, followed_user_id)

    def remove_follow(self, follower_user_id: UUID, followed_user_id: UUID) -> None:
        logger.info("Removing follow: follower=%s, followed=%s", follower_user_id, followed_user_id)
        self.follow_repository.remove_follow(
            follower_user_id=follower_user_id,
            followed_user_id=followed_user_id
        )
        logger.info("Follow removed successfully: follower=%s, followed=%s",
                      follower_user_id, followed_user_id)

    def remove_follows_between_users(self, user_id_1: UUID, user_id_2: UUID) -> None:
        logger.info("Removing bidirectional follows between users: user1=%s, user2=%s",
                      user_id_1, user_id_2)
        self.follow_repository.remove_follows_between_users(
            user_id_1=user_id_1,
            user_id_2=user_id_2
        )
        logger.info("Bidirectional follows removed successfully: user1=%s, user2=%s",
                      user_id_1, user_id_2)
