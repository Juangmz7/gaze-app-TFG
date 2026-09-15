
from abc import ABC, abstractmethod
from uuid import UUID


class SemanticPostRetrievalRepository(ABC):
    @abstractmethod
    def get_similar_posts(self, user_id: UUID, limit: int) -> list[UUID]:
        pass