from abc import ABC, abstractmethod
from collections.abc import Collection
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
        excluded_post_ids: Collection[UUID],
    ) -> list[tuple[UUID, float]]:
        """Recent, low-view posts the user has not seen yet, skipping
        excluded_post_ids (the posts already chosen by the ranker)."""
        pass