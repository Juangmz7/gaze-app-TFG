
from abc import ABC, abstractmethod
from uuid import UUID

from pipeline.model.user.user_creator_features import UserCreatorFeatures


class UserCreatorFeaturesRepository(ABC):
    @abstractmethod
    def get_user_creator_features(
            self,
            user_id: UUID,
            creator_id: UUID,
    ) -> UserCreatorFeatures | None:
        pass

    @abstractmethod
    def get_user_creator_features_for_update(
            self,
            user_id: UUID,
            creator_id: UUID,
    ) -> UserCreatorFeatures | None:
        pass

    @abstractmethod
    def save(self, user_creator_features: UserCreatorFeatures) -> None:
        pass
