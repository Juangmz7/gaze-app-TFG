
from abc import ABC, abstractmethod
from uuid import UUID


class CollaborativePostRetrievalRepository(ABC):
    @abstractmethod
    def has_semantic_signal(self, user_id: UUID) -> bool:
        pass

    @abstractmethod
    def get_similar_users(self, user_id: UUID, limit: int) -> list[tuple[UUID, float]]:
        """
        Get similar users based on semantic embeddings.
        :param user_id: The user ID to find similar users for.
        :param limit: The maximum number of similar users to return.
        :return: A list of (user_id, similarity_score) tuples ordered by descending similarity.
        """
        pass

    @abstractmethod
    def get_posts_ordered_by_user_affinity(
        self,
        similar_users: list[tuple[UUID, float]],
        user_id: UUID,
        creators_per_user_limit: int,
        posts_per_creator_limit: int,
    ) -> list[UUID]:
        """
        Get posts ordered by user affinity, excluding seen or blocked ones.
        :param similar_users: List of (similar_user_id, similarity_score) tuples.
        :param user_id: The target user ID for whom to retrieve posts.
        :param creators_per_user_limit: Max number of top creators to consider per similar user.
        :param posts_per_creator_limit: Max number of posts to retrieve per (similar_user, creator) pair.
        :return: A list of post IDs ordered by combined affinity score (similarity × creator_affinity).
        """
        pass