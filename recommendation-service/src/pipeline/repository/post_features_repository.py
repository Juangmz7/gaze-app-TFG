
from typing import Optional
from uuid import UUID

from pipeline.model.post.post_features import PostFeatures


class PostFeaturesRepository:
    def __init__(self):
        pass
    
    def get_post_features(self, post_id: UUID) -> PostFeatures:
        pass

    def save(self, post_features: PostFeatures) -> None:
        pass

    def delete(self, post_id: UUID) -> None:
        pass

    def update_post_collab(
            self,
            post_id: UUID,
            collab_id: Optional[UUID],
            collab_title: str | None,
    ) -> None:
        pass
