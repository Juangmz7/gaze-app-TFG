from contextlib import nullcontext
import logging
from typing import Any

from follow.command.follow_commands import DeleteFollowCommand
from follow.service.follow_service import FollowService
from pipeline.config.constants import UNFOLLOW_PENALISATION_WEIGHT
from pipeline.repository.user_features_repository import UserFeaturesRepository

logger = logging.getLogger(__name__)


class DeleteFollowUsecase:
    def __init__(
            self,
            follow_service: FollowService,
            user_features_repository: UserFeaturesRepository,
            transaction_manager: Any | None = None,
    ):
        self.follow_service = follow_service
        self.user_features_repository = user_features_repository
        self.transaction_manager = transaction_manager

    def execute(self, command: DeleteFollowCommand) -> None:
        transaction = (
            self.transaction_manager.transaction()
            if self.transaction_manager is not None
            else nullcontext()
        )
        with transaction:
            self.follow_service.remove_follow(
                follower_user_id=command.follower_user_id,
                followed_user_id=command.followed_user_id,
            )
            self._penalize_follower_embedding(command)

    def _penalize_follower_embedding(self, command: DeleteFollowCommand) -> None:
        followed_features = self.user_features_repository.get_user_features(
            command.followed_user_id,
        )
        if followed_features is None:
            logger.warning(
                "Followed user features not found for unfollow embedding: followed_user_id=%s",
                command.followed_user_id,
            )
            return

        follower_features = self.user_features_repository.get_user_features_for_update(
            command.follower_user_id,
        )
        if follower_features is None:
            logger.warning(
                "Follower user features not found for unfollow embedding: follower_user_id=%s",
                command.follower_user_id,
            )
            return

        follower_features.apply_semantic_interaction(
            post_semantic_embedding=followed_features.semantic_embedding,
            embedding_weight=-UNFOLLOW_PENALISATION_WEIGHT,
            source_weight=1.0,
            occurred_at=command.occurred_at,
        )
        self.user_features_repository.save(follower_features)
