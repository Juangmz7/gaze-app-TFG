from uuid import UUID

from post.model.user_post_interactions import UserPostInteractions


class UserPostInteractionsRepository:
    def get(self, post_id: UUID, user_id: UUID) -> UserPostInteractions | None:
        pass

    def save(self, interactions: UserPostInteractions) -> None:
        pass

