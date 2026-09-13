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

    @abstractmethod
    def get_all_by_user(
        self,
        post_ids: list[UUID],
        user_id: UUID,
    ) -> dict[UUID, UserPostInteractions]:
        pass

    @abstractmethod
    def save_all(self, interactions: list[UserPostInteractions]) -> None:
        pass
