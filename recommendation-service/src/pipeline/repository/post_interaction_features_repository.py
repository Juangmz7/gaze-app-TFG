import abc
from datetime import datetime
from uuid import UUID

from pipeline.model.post.post_interaction_features import PostInteractionFeatures


class PostInteractionFeaturesRepository(abc.ABC):
    @abc.abstractmethod
    def get_for_update(self, post_id: UUID) -> PostInteractionFeatures | None:
        pass

    @abc.abstractmethod
    def save(self, features: PostInteractionFeatures) -> None:
        pass

    @abc.abstractmethod
    def create_empty(self, post_id: UUID, created_at: datetime) -> None:
        pass

    @abc.abstractmethod
    def delete(self, post_id: UUID) -> None:
        pass
