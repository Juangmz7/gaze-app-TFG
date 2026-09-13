from contextlib import nullcontext
import logging
from typing import Any

from follow.command.follow_commands import CreateFollowCommand
from follow.service.follow_service import FollowService
from pipeline.config.constants import FOLLOW_WEIGHT
from pipeline.repository.user_features_repository import UserFeaturesRepository

logger = logging.getLogger(__name__)


class CreateFollowUsecase:
    def __init__(
            self,
            follow_service: FollowService,
            user_features_repository: UserFeaturesRepository,
            transaction_manager: Any | None = None,
    ):
        self.follow_service = follow_service
        self.user_features_repository = user_features_repository
        self.transaction_manager = transaction_manager

    def execute(self, command: CreateFollowCommand) -> None:
        transaction = (
            self.transaction_manager.transaction()
            if self.transaction_manager is not None
            else nullcontext()
        )
        with transaction:
            self.follow_service.create_follow(
                follower_user_id=command.follower_user_id,
                followed_user_id=command.followed_user_id,
                created_at=command.occurred_at,
            )
            self._update_follower_embedding(command)

    def _update_follower_embedding(self, command: CreateFollowCommand) -> None:
        followed_features = self.user_features_repository.get_user_features(
            command.followed_user_id,
        )
        if followed_features is None:
            logger.warning(
                "Followed user features not found for follow embedding: followed_user_id=%s",
                command.followed_user_id,
            )
            return

        follower_features = self.user_features_repository.get_user_features_for_update(
            command.follower_user_id,
        )
        if follower_features is None:
            logger.warning(
                "Follower user features not found for follow embedding: follower_user_id=%s",
                command.follower_user_id,
            )
            return

        follower_features.apply_semantic_interaction(
            post_semantic_embedding=followed_features.semantic_embedding,
            embedding_weight=FOLLOW_WEIGHT,
            source_weight=1.0,
            occurred_at=command.occurred_at,
        )
        self.user_features_repository.save(follower_features)
