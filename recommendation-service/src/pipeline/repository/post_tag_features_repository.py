
from abc import ABC, abstractmethod
from uuid import UUID

from pipeline.model.post.post_tag_features import PostTagFeatures


class PostTagFeaturesRepository(ABC):
    @abstractmethod
    def get_post_tag_features(self, user_id: UUID, tags: list[str]) -> list[PostTagFeatures]:
        pass

    @abstractmethod
    def get_post_tag_features_for_update(
            self,
            user_id: UUID,
            tags: list[str],
    ) -> list[PostTagFeatures]:
        pass

    @abstractmethod
    def create_if_absent(self, post_tag_features: list[PostTagFeatures]) -> None:
        """Insert each row only if it does not already exist (INSERT … ON CONFLICT DO NOTHING).

        Safe to call concurrently: races are resolved at the database level and
        the loser is silently ignored.
        """
        pass

    @abstractmethod
    def save_all(self, post_tag_features: list[PostTagFeatures]) -> None:
        pass
