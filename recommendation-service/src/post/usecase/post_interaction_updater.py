from datetime import datetime
import logging
from contextlib import nullcontext
from typing import Any, Sequence
from uuid import UUID

from pipeline.exceptions.exceptions import (
    PostFeaturesNotFoundException,
    PostTagFeaturesNotFoundException,
    UserCreatorFeaturesNotFoundException,
)
from pipeline.model.interaction.interaction_metric_update import InteractionMetricUpdate
from pipeline.repository.post_features_repository import PostFeaturesRepository
from pipeline.repository.post_tag_features_repository import PostTagFeaturesRepository
from pipeline.repository.user_creator_features_repository import UserCreatorFeaturesRepository
from pipeline.repository.user_features_repository import UserFeaturesRepository
from rabbitmq.event.post.post_events import InteractionSource
from shared.helpers import get_view_source_weight

logger = logging.getLogger(__name__)


class PostInteractionUpdater:
    def __init__(
            self,
            user_creator_features_repository: UserCreatorFeaturesRepository,
            post_tag_features_repository: PostTagFeaturesRepository,
            user_features_repository: UserFeaturesRepository,
            post_features_repository: PostFeaturesRepository,
            transaction_manager: Any | None = None,
    ):
        self.user_creator_features_repository = user_creator_features_repository
        self.post_tag_features_repository = post_tag_features_repository
        self.user_features_repository = user_features_repository
        self.post_features_repository = post_features_repository
        self.transaction_manager = transaction_manager

    def apply(
            self,
            post_id: UUID,
            user_id: UUID,
            metric_updates: Sequence[InteractionMetricUpdate],
            embedding_weight: float,
            occurred_at: datetime,
            source: InteractionSource | None = None,
    ) -> None:
        updates = list(metric_updates)
        if not updates:
            raise ValueError("At least one interaction metric update is required")

        transaction = (
            self.transaction_manager.transaction()
            if self.transaction_manager is not None
            else nullcontext()
        )
        with transaction:
            post_features = self.post_features_repository.get_post_features(post_id)
            if post_features is None:
                raise PostFeaturesNotFoundException(post_id)

            user_creator_features = (
                self.user_creator_features_repository.get_user_creator_features_for_update(
                    user_id,
                    post_features.creator_id,
                )
            )
            if user_creator_features is None:
                raise UserCreatorFeaturesNotFoundException(post_id, user_id)

            post_tags_features = self.post_tag_features_repository.get_post_tag_features_for_update(
                user_id,
                post_features.tags,
            )
            expected_tags = set(post_features.tags)
            found_tags = {features.tag_name for features in post_tags_features}
            if found_tags != expected_tags:
                raise PostTagFeaturesNotFoundException(post_id)

            user_creator_features.apply_interaction_updates(
                updates,
                occurred_at,
            )
            for post_tag_feature in post_tags_features:
                post_tag_feature.apply_interaction_updates(
                    updates,
                    occurred_at,
                )

            self._update_user_semantic_embedding(
                user_id,
                post_features.semantic_embedding,
                embedding_weight,
                source,
                occurred_at,
            )

            self.user_creator_features_repository.save(user_creator_features)
            self.post_tag_features_repository.save_all(post_tags_features)

    def _update_user_semantic_embedding(
            self,
            user_id: UUID,
            post_semantic_embedding: list[float],
            weight: float,
            source: InteractionSource | None,
            occurred_at: datetime,
    ) -> None:
        user_features = self.user_features_repository.get_user_features_for_update(user_id)
        if user_features is None:
            logger.warning("User features not found for interaction embedding: user_id=%s", user_id)
            return

        source_weight = get_view_source_weight(source) if source is not None else 1.0
        user_features.apply_semantic_interaction(
            post_semantic_embedding=post_semantic_embedding,
            embedding_weight=weight,
            source_weight=source_weight,
            occurred_at=occurred_at,
        )
        self.user_features_repository.save(user_features)
