import logging
from typing import TYPE_CHECKING

from pipeline.model.user.user_features import UserFeatures
from pipeline.repository.user_features_repository import UserFeaturesRepository
from shared.helpers import build_user_semantic_text
from user.command.user_commands import UpdateUserCommand

if TYPE_CHECKING:
    from pipeline.service.semantic_embedding_model_service import SemanticEmbeddingModelEncoder


logger = logging.getLogger(__name__)


class UpdateUserUsecase:
    def __init__(
            self,
            user_features_repository: UserFeaturesRepository,
            semantic_embedding_model_service: "SemanticEmbeddingModelEncoder",
    ):
        self.user_features_repository = user_features_repository
        self.semantic_embedding_model_service = semantic_embedding_model_service

    def execute(self, command: UpdateUserCommand) -> None:
        semantic_text = build_user_semantic_text(
            description=command.bio.description if command.bio is not None else None,
            social_media=command.bio.socialMedia if command.bio is not None else None,
        )
        self.user_features_repository.save(
            UserFeatures(
                user_id=command.user_id,
                semantic_embedding=self.semantic_embedding_model_service.encode(semantic_text),
                last_updated_at=command.updated_at or command.occurred_at,
                has_semantic_signal=True,
            )
        )
        logger.info(f"User {command.user_id} updated successfully")
