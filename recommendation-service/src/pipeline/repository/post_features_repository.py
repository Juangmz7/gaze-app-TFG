
from abc import ABC, abstractmethod
from typing import Optional
from uuid import UUID

from pipeline.model.post.post_features import PostFeatures


class PostFeaturesRepository(ABC):
    @abstractmethod
    def get_post_features(self, post_id: UUID) -> PostFeatures | None:
        pass

    @abstractmethod
    def save(self, post_features: PostFeatures) -> None:
        pass

    @abstractmethod
    def delete(self, post_id: UUID) -> None:
        pass

    @abstractmethod
    def update_post_collab(
            self,
            post_id: UUID,
            collab_id: Optional[UUID],
            collab_title: str | None,
    ) -> None:
        pass

    @abstractmethod
    def clear_collab_for_posts(self, post_ids: list[UUID]) -> None:
        pass
