from abc import ABC, abstractmethod
from uuid import UUID

from post.model.user_post_interactions import UserPostInteractions


class UserPostInteractionsRepository(ABC):
    @abstractmethod
    def get(self, post_id: UUID, user_id: UUID) -> UserPostInteractions | None:
        pass

    @abstractmethod
    def save(self, interactions: UserPostInteractions) -> None:
        pass
