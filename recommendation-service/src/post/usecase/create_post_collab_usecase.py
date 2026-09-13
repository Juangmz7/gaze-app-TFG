from pipeline.model.post.post_features import PostFeatures
from pipeline.repository.post_features_repository import PostFeaturesRepository
from pipeline.service.semantic_embedding_model_service import SemanticEmbeddingModelService
from post.command.post_commands import CreatePostCollabCommand
from post.model.collab import Collab
from post.repository.collab_repository import CollabRepository
from shared.helpers import build_post_semantic_text


class CreatePostCollabUsecase:
    def __init__(
            self,
            collab_repository: CollabRepository,
            post_features_repository: PostFeaturesRepository,
            semantic_embedding_model_service: SemanticEmbeddingModelService,
    ):
        self.collab_repository = collab_repository
        self.post_features_repository = post_features_repository
        self.semantic_embedding_model_service = semantic_embedding_model_service

    def execute(self, command: CreatePostCollabCommand) -> None:
        self.collab_repository.save(
            Collab(
                collab_id=command.collab_id,
                title=command.title,
                created_by=command.created_by,
                status=command.collab_status,
                created_at=command.collab_created_at,
            )
        )

        semantic_text = build_post_semantic_text(
            description=command.description,
            tags=command.post_tags,
            tagged_users=command.tagged_users,
            collab_title=command.title,
        )
        self.post_features_repository.save(
            PostFeatures(
                post_id=command.post_id,
                creator_id=command.user_id,
                collab_id=command.collab_id,
                collab_title=command.title,
                description=command.description,
                tags=list(command.post_tags),
                tagged_users_ids=list(command.tagged_users),
                semantic_embedding=self.semantic_embedding_model_service.encode(semantic_text),
                created_at=command.post_created_at,
            )
        )
