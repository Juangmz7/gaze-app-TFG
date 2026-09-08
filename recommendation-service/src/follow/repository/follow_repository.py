
from abc import ABC, abstractmethod
from uuid import UUID

from follow.model.follow import Follow


class FollowRepository(ABC):
    @abstractmethod
    def create_follow(self, follow: Follow) -> None:
        pass

    @abstractmethod
    def remove_follow(self, follower_user_id: UUID, followed_user_id: UUID) -> None:
        pass

    @abstractmethod
    def remove_follows_between_users(self, user_id_1: UUID, user_id_2: UUID) -> None:
        pass
