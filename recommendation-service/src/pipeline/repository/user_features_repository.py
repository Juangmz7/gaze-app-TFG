
from pipeline.model.user.user_features import UserFeatures


class UserFeaturesRepository:
    def get_user_features(self, user_id: str) -> UserFeatures:
        pass

    def save(self, user_features: UserFeatures) -> None:
        pass
