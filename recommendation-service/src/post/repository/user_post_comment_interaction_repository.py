from uuid import UUID

from post.model.user_post_comment_interaction import UserPostCommentInteraction


class UserPostCommentInteractionRepository:
    def get(self, comment_id: UUID, user_id: UUID) -> UserPostCommentInteraction | None:
        pass

    def save(self, interaction: UserPostCommentInteraction) -> None:
        pass

