
from uuid import UUID

from pipeline.model.post.post_features import PostFeatures


class PostFeaturesRepository:
    def __init__(self):
        pass
    
    def get_post_features(self, post_id: UUID) -> PostFeatures:
        pass