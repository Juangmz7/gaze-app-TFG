from datetime import datetime
import logging
from uuid import UUID

from pipeline.exceptions.exceptions import (
    PostFeaturesNotFoundException,
    PostTagFeaturesNotFoundException,
    UserCreatorFeaturesNotFoundException,
)
from pipeline.model.interaction.decayed_interaction_stats import DecayedInteractionStats
from pipeline.model.interaction.raw_interaction_stats import RawInteractionStats
from pipeline.repository.post_features_repository import PostFeaturesRepository
from pipeline.repository.post_tag_features_repository import PostTagFeaturesRepository
from pipeline.repository.user_creator_features_repository import UserCreatorFeaturesRepository
from pipeline.repository.user_features_repository import UserFeaturesRepository
from rabbitmq.event.post.post_events import InteractionSource
from shared.helpers import decay, get_view_source_weight

logger = logging.getLogger(__name__)


class PostInteractionUpdater:
    def __init__(
            self,
            user_creator_features_repository: UserCreatorFeaturesRepository,
            post_tag_features_repository: PostTagFeaturesRepository,
            user_features_repository: UserFeaturesRepository,
            post_features_repository: PostFeaturesRepository,
    ):
        self.user_creator_features_repository = user_creator_features_repository
        self.post_tag_features_repository = post_tag_features_repository
        self.user_features_repository = user_features_repository
        self.post_features_repository = post_features_repository

    def apply(
            self,
            post_id: UUID,
            user_id: UUID,
            metric_name: str,
            raw_delta: int,
            embedding_weight: float,
            source: InteractionSource | None = None,
    ) -> None:
        user_creator_features = self.user_creator_features_repository.get_user_creator_features(
            post_id, user_id
        )
        if user_creator_features is None:
            raise UserCreatorFeaturesNotFoundException(post_id, user_id)

        post_features = self.post_features_repository.get_post_features(post_id)
        if post_features is None:
            raise PostFeaturesNotFoundException(post_id)

        post_tags_features = self.post_tag_features_repository.getPostsTagsFeatures(
            user_id,
            post_features.tags,
        )
        if post_tags_features is None:
            raise PostTagFeaturesNotFoundException(post_id)

        raw_stats = [tag_features.raw_interaction_stats for tag_features in post_tags_features]
        raw_stats.append(user_creator_features.raw_interaction_stats)
        self._update_raw_interaction_stats(raw_stats, metric_name, raw_delta)

        decayed_stats = [
            (tag_features.decayed_interaction_stats, tag_features.last_updated_at)
            for tag_features in post_tags_features
        ]
        decayed_stats.append(
            (
                user_creator_features.decayed_interaction_stats,
                user_creator_features.last_updated_at,
            )
        )
        self._update_decayed_interaction_stats(
            decayed_stats,
            metric_name,
            embedding_weight,
        )

        user_creator_features.recalculate_affinity_score()
        for post_tag_feature in post_tags_features:
            post_tag_feature.recalculate_affinity_score()

        self._update_user_semantic_embedding(
            user_id,
            post_features.semantic_embedding,
            embedding_weight,
            source,
        )

        self.user_creator_features_repository.save(user_creator_features)
        self.post_tag_features_repository.save_all(post_tags_features)

    def _update_raw_interaction_stats(
            self,
            interaction_stats: list[RawInteractionStats],
            metric_name: str,
            raw_delta: int,
    ) -> None:
        for stats in interaction_stats:
            setattr(stats, metric_name, getattr(stats, metric_name) + raw_delta)

    def _update_decayed_interaction_stats(
            self,
            decayed_interaction_stats: list[tuple[DecayedInteractionStats, datetime]],
            metric_name: str,
            weight: float,
    ) -> None:
        for stats, last_updated_at in decayed_interaction_stats:
            setattr(
                stats,
                metric_name,
                getattr(stats, metric_name) * decay(last_updated_at) + weight,
            )

    def _update_user_semantic_embedding(
            self,
            user_id: UUID,
            post_semantic_embedding: list[float],
            weight: float,
            source: InteractionSource | None,
    ) -> None:
        user_features = self.user_features_repository.get_user_features(user_id)
        if user_features is None:
            logger.warning("User features not found for interaction embedding: user_id=%s", user_id)
            return

        source_weight = get_view_source_weight(source) if source is not None else 1.0
        decayed_user_embedding = [
            value * decay(user_features.last_updated_at)
            for value in user_features.semantic_embedding
        ]
        weighted_post_embedding = [
            value * weight * source_weight
            for value in post_semantic_embedding
        ]

        user_features.semantic_embedding = [
            user_value + post_value
            for user_value, post_value in zip(
                decayed_user_embedding,
                weighted_post_embedding,
                strict=True,
            )
        ]
        self.user_features_repository.save(user_features)
