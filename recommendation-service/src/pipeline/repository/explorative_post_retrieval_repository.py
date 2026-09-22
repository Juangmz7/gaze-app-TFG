from abc import ABC, abstractmethod
from uuid import UUID



class ExplorativePostRetrievalRepository(ABC):

    @abstractmethod
    def get_popular_posts(
        self,
        user_id: UUID,
        limit: int,
    ) -> list[tuple[UUID, float]]:
        pass

    @abstractmethod
    def get_random_posts(
        self,
        user_id: UUID,
        limit: int,
    ) -> list[tuple[UUID, float]]:
        pass

    @abstractmethod
    def get_unseen_tags_posts(
        self,
        user_id: UUID,
        limit: int,
    ) -> list[tuple[UUID, float]]:
        pass

    @abstractmethod
    def get_cold_start_posts(
        self,
        user_id: UUID,
        limit: int,
    ) -> list[tuple[UUID, float]]:
        pass