from datetime import datetime
import logging
from uuid import UUID

from pipeline.config.constants import POST_LIKE_WEIGHT
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
from post.command.post_commands import CreatePostLikeCommand
from rabbitmq.event.post.post_events import InteractionSource
from shared.helpers import decay, get_view_source_weight

logger = logging.getLogger(__name__)

class CreatePostLikeUsecase:
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

    def execute(self, command: CreatePostLikeCommand) -> None:
        logger.info(
            "Processing post like: post_id=%s, user_id=%s, source=%s, feed_position=%s",
            command.post_id,
            command.user_id,
            command.source,
            command.feed_position,
        )

        user_creator_features = self.user_creator_features_repository.get_user_creator_features(
            command.post_id, command.user_id
        )
        if user_creator_features is None:
            logger.warning(
                "User creator features not found for post like: post_id=%s, user_id=%s",
                command.post_id,
                command.user_id,
            )
            raise UserCreatorFeaturesNotFoundException(command.post_id, command.user_id)

        post_features = self.post_features_repository.get_post_features(command.post_id)
        if post_features is None:
            logger.warning(
                "Post features not found for post like: post_id=%s, user_id=%s",
                command.post_id,
                command.user_id,
            )
            raise PostFeaturesNotFoundException(command.post_id)

        post_tags_features = self.post_tag_features_repository.getPostsTagsFeatures(
            command.user_id,
            post_features.tags,
        )
        if post_tags_features is None:
            logger.warning(
                "Post tag features not found for post like: post_id=%s, user_id=%s, tag_count=%s",
                command.post_id,
                command.user_id,
                len(post_features.tags),
            )
            raise PostTagFeaturesNotFoundException(command.post_id)

        logger.debug(
            "Updating post like interaction stats: post_id=%s, user_id=%s, tag_count=%s",
            command.post_id,
            command.user_id,
            len(post_tags_features),
        )

        raw_interaction_stats = [
            tag_features.raw_interaction_stats for tag_features in post_tags_features
        ]
        raw_interaction_stats.append(user_creator_features.raw_interaction_stats)
        self._update_raw_interaction_stats(raw_interaction_stats)

        decayed_interaction_stats = [
            (tag_features.decayed_interaction_stats, tag_features.last_updated_at)
            for tag_features in post_tags_features
        ]
        decayed_interaction_stats.append(
            (
                user_creator_features.decayed_interaction_stats,
                user_creator_features.last_updated_at,
            )
        )
        self._update_decayed_interaction_stats(decayed_interaction_stats)

        user_creator_features.recalculate_affinity_score()
        for post_tag_feature in post_tags_features:
            post_tag_feature.recalculate_affinity_score()

        self._update_user_semantic_embedding(
            command.user_id,
            command.source,
            post_features.semantic_embedding,
        )

        self.user_creator_features_repository.save(user_creator_features)
        self.post_tag_features_repository.save_all(post_tags_features)
        logger.info(
            "Post like processed successfully: post_id=%s, user_id=%s",
            command.post_id,
            command.user_id,
        )

    def _update_raw_interaction_stats(
            self,
            interactions_stats: list[RawInteractionStats],
    ) -> None:
        for interaction_stats in interactions_stats:
            interaction_stats.likes += 1

    def _update_decayed_interaction_stats(
            self,
            decayed_interactions_stats: list[tuple[DecayedInteractionStats, datetime]],
    ) -> None:
        for decayed_interaction_stats, last_updated_at in decayed_interactions_stats:
            decayed_interaction_stats.likes = (
                decayed_interaction_stats.likes * decay(last_updated_at) + 1
            )

    def _update_user_semantic_embedding(
            self,
            user_id: UUID,
            source: InteractionSource,
            post_semantic_embedding: list[float],
    ) -> None:
        logger.debug(
            "Updating user semantic embedding for post like: user_id=%s, source=%s",
            user_id,
            source,
        )
        user_features = self.user_features_repository.get_user_features(user_id)
        decayed_user_embedding = [
            value * decay(user_features.last_updated_at)
            for value in user_features.semantic_embedding
        ]
        weighted_post_embedding = [
            value * POST_LIKE_WEIGHT * get_view_source_weight(source)
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
