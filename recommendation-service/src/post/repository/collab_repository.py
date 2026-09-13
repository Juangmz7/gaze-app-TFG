from abc import ABC, abstractmethod
from uuid import UUID

from post.model.collab import Collab


class CollabRepository(ABC):
    @abstractmethod
    def get(self, collab_id: UUID) -> Collab | None:
        pass

    @abstractmethod
    def save(self, collab: Collab) -> None:
        pass

    @abstractmethod
    def delete(self, collab_id: UUID) -> None:
        pass

    @abstractmethod
    def find_posts_id_by_collab_id(self, collab_id: UUID) -> list[UUID]:
        pass
