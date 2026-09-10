
from abc import ABC, abstractmethod
from uuid import UUID

from pipeline.model.user.user_features import UserFeatures


class UserFeaturesRepository(ABC):
    @abstractmethod
    def get_user_features(self, user_id: UUID) -> UserFeatures | None:
        pass

    @abstractmethod
    def get_user_features_for_update(self, user_id: UUID) -> UserFeatures | None:
        pass

    @abstractmethod
    def save(self, user_features: UserFeatures) -> None:
        pass
