from typing import TYPE_CHECKING

from pipeline.model.user.user_features import UserFeatures
from pipeline.repository.user_features_repository import UserFeaturesRepository
from shared.helpers import build_user_semantic_text
from user.command.user_commands import RegisterUserCommand

if TYPE_CHECKING:
    from pipeline.service.semantic_embedding_model_service import SemanticEmbeddingModelEncoder


class RegisterUserUsecase:
    def __init__(
            self,
            user_features_repository: UserFeaturesRepository,
            semantic_embedding_model_service: "SemanticEmbeddingModelEncoder",
    ):
        self.user_features_repository = user_features_repository
        self.semantic_embedding_model_service = semantic_embedding_model_service

    def execute(self, command: RegisterUserCommand) -> None:
        semantic_text = build_user_semantic_text(
            description=command.bio.description if command.bio is not None else None,
        )

        has_semantic_signal = bool(semantic_text)

        semantic_embedding = (
            self.semantic_embedding_model_service.encode(semantic_text)
            if has_semantic_signal
            else self.semantic_embedding_model_service.zero_embedding()
        )

        self.user_features_repository.save(
            UserFeatures(
                user_id=command.user_id,
                semantic_embedding=semantic_embedding,
                last_updated_at=command.occurred_at,
                has_semantic_signal=has_semantic_signal,
            )
        )
