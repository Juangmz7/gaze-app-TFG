from pipeline.model.post.post_features import PostFeatures
from pipeline.repository.post_features_repository import PostFeaturesRepository
from pipeline.repository.semantic_embedding_repository import SemanticEmbeddingRepository
from post.command.post_commands import CreatePostCommand
from shared.helpers import build_post_semantic_text


class CreatePostUsecase:
    def __init__(
            self,
            post_features_repository: PostFeaturesRepository,
            semantic_embedding_repository: SemanticEmbeddingRepository,
    ):
        self.post_features_repository = post_features_repository
        self.semantic_embedding_repository = semantic_embedding_repository

    def execute(self, command: CreatePostCommand) -> None:
        semantic_text = build_post_semantic_text(
            description=command.description,
            tags=command.post_tags,
            tagged_users=command.tagged_users,
        )
        post_features = PostFeatures(
            post_id=command.post_id,
            creator_id=command.user_id,
            collab_id=command.collab_id,
            collab_title=None,
            description=command.description,
            tags=list(command.post_tags),
            tagged_users_ids=list(command.tagged_users),
            semantic_embedding=self.semantic_embedding_repository.embed_post(semantic_text),
            created_at=command.created_at,
        )
        self.post_features_repository.save(post_features)
