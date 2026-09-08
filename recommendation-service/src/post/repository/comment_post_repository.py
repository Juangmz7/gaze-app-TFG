from uuid import UUID


class CommentPostRepository:
    def save_comment_post(self, comment_id: UUID, post_id: UUID) -> None:
        pass

    def find_post_id_by_comment_id(self, comment_id: UUID) -> UUID | None:
        pass

    def delete_comment_post(self, comment_id: UUID) -> None:
        pass

