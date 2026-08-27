
from pipeline.model.user.user_creator_features import UserCreatorFeatures


class UserCreatorFeaturesRepository:

    def get_user_creator_features(self, post_id: str, user_id: str) -> UserCreatorFeatures:
        pass

    def save(self, user_creator_features: UserCreatorFeatures) -> None:
        pass
