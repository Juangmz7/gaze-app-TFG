
from uuid import UUID

from pipeline.model.post.post_tag_features import PostTagFeatures


class PostTagFeaturesRepository:
    def getPostsTagsFeatures(self, user_id: UUID, tags: list[str]) -> list[PostTagFeatures]:
        pass