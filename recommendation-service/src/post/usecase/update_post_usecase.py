import logging

from pipeline.model.post.post_features import PostFeatures
from pipeline.repository.post_features_repository import PostFeaturesRepository
from pipeline.service.semantic_embedding_model_service import SemanticEmbeddingModelService
from post.command.post_commands import UpdatePostCommand
from post.repository.collab_repository import CollabRepository
from shared.helpers import build_post_semantic_text

logger = logging.getLogger(__name__)


class UpdatePostUsecase:
    def __init__(
            self,
            post_features_repository: PostFeaturesRepository,
            semantic_embedding_model_service: SemanticEmbeddingModelService,
            collab_repository: CollabRepository,
    ):
        self.post_features_repository = post_features_repository
        self.semantic_embedding_model_service = semantic_embedding_model_service
        self.collab_repository = collab_repository

    def execute(self, command: UpdatePostCommand) -> None:
        existing = self.post_features_repository.get_post_features(command.post_id)
        if existing is None:
            logger.warning("Ignoring post update for unknown post: post_id=%s", command.post_id)
            return

        collab_title = existing.collab_title
        if command.collab_id is not None:
            collab = self.collab_repository.get(command.collab_id)
            collab_title = collab.title if collab is not None else collab_title

        semantic_text = build_post_semantic_text(
            description=command.description,
            tags=command.post_tags,
            tagged_users=command.tagged_users,
            collab_title=collab_title,
        )
        self.post_features_repository.save(
            PostFeatures(
                post_id=command.post_id,
                creator_id=command.user_id,
                collab_id=command.collab_id,
                collab_title=collab_title,
                description=command.description,
                tags=list(command.post_tags),
                tagged_users_ids=list(command.tagged_users),
                semantic_embedding=self.semantic_embedding_model_service.encode(semantic_text),
                created_at=command.created_at,
            )
        )
