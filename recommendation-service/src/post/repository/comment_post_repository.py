from abc import ABC, abstractmethod
from uuid import UUID


class CommentPostRepository(ABC):
    @abstractmethod
    def save_comment_post(self, comment_id: UUID, post_id: UUID) -> None:
        pass

    @abstractmethod
    def find_post_id_by_comment_id(self, comment_id: UUID) -> UUID | None:
        pass

    @abstractmethod
    def delete_comment_post(self, comment_id: UUID) -> None:
        pass
