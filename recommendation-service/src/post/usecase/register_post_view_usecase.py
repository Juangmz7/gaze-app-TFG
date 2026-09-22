import logging
from contextlib import nullcontext
from typing import Any
from uuid import UUID

from pipeline.config.constants import FAST_SKIP_WEIGHT, POST_VIEW_WEIGHT
from pipeline.exceptions.exceptions import (
    PostFeaturesNotFoundException,
    PostInteractionFeaturesNotFoundException,
)
from pipeline.model.interaction.interaction_metric_update import InteractionMetricUpdate
from pipeline.model.post.post_features import PostFeatures
from pipeline.model.post.post_tag_features import PostTagFeatures
from pipeline.model.user.user_creator_features import UserCreatorFeatures
from pipeline.repository.post_features_repository import PostFeaturesRepository
from pipeline.repository.post_interaction_features_repository import PostInteractionFeaturesRepository
from pipeline.repository.post_tag_features_repository import PostTagFeaturesRepository
from pipeline.repository.user_creator_features_repository import UserCreatorFeaturesRepository
from pipeline.repository.user_features_repository import UserFeaturesRepository
from post.command.post_commands import RegisterPostViewCommand
from rabbitmq.event.post.post_events import PostViewExitReason
from shared.enum.interaction_metric import InteractionMetric
from shared.helpers import get_view_source_weight

logger = logging.getLogger(__name__)


class RegisterPostViewUsecase:
    def __init__(
            self,
            user_creator_features_repository: UserCreatorFeaturesRepository,
            post_tag_features_repository: PostTagFeaturesRepository,
            post_features_repository: PostFeaturesRepository,
            user_features_repository: UserFeaturesRepository,
            post_interaction_features_repository: PostInteractionFeaturesRepository,
            transaction_manager: Any | None = None,
    ):
        self.user_creator_features_repository = user_creator_features_repository
        self.post_tag_features_repository = post_tag_features_repository
        self.post_features_repository = post_features_repository
        self.user_features_repository = user_features_repository
        self.post_interaction_features_repository = post_interaction_features_repository
        self.transaction_manager = transaction_manager

    def execute(self, command: RegisterPostViewCommand) -> None:
        watch_percent = self._calculate_watch_percent(command.duration_ms, command.time_watched_ms)
        is_fast_skip = self._is_fast_skip(command.completion_percent, command.exit_reason)
        metric_updates, embedding_weight = self._build_metric_updates(
            command.time_watched_ms, is_fast_skip
        )

        with self._transaction():
            post_features, post_interaction = self._get_post_entities(command.post_id)
            user_creator, post_tags = self._get_user_entities(
                command.user_id, post_features, command.occurred_at
            )

            self._apply_updates(
                user_creator, post_tags, post_interaction,
                metric_updates, embedding_weight, command.occurred_at,
                is_fast_skip, watch_percent
            )

            self._update_user_semantic_embedding(
                command.user_id, post_features.semantic_embedding,
                embedding_weight, command.source, command.occurred_at,
            )

            self._save_entities(user_creator, post_tags, post_interaction)

    def _calculate_watch_percent(self, duration_ms: int, time_watched_ms: int) -> float:
        duration_seconds = duration_ms / 1000.0
        watched_seconds = time_watched_ms / 1000.0
        if duration_seconds > 0:
            return watched_seconds / duration_seconds
        return 0.0

    def _is_fast_skip(self, completion_percent: int, exit_reason: PostViewExitReason) -> bool:
        return (
            completion_percent < 5
            and exit_reason == PostViewExitReason.SCROLL_NEXT
        )

    def _build_metric_updates(self, time_watched_ms: int, is_fast_skip: bool) -> tuple[list[InteractionMetricUpdate], float]:
        watched_seconds = time_watched_ms / 1000.0
        updates = [
            InteractionMetricUpdate(
                metric=InteractionMetric.IMPRESSIONS,
                raw_delta=1,
                decayed_delta=1,
            ),
            InteractionMetricUpdate(
                metric=InteractionMetric.WATCH_TIME,
                raw_delta=watched_seconds,
                decayed_delta=watched_seconds,
            ),
        ]

        if is_fast_skip:
            updates.append(InteractionMetricUpdate(
                metric=InteractionMetric.FAST_SKIPS,
                raw_delta=1,
                decayed_delta=1,
            ))
            return updates, FAST_SKIP_WEIGHT
            
        return updates, POST_VIEW_WEIGHT

    def _transaction(self):
        return (
            self.transaction_manager.transaction()
            if self.transaction_manager is not None
            else nullcontext()
        )

    def _get_post_entities(self, post_id: UUID):
        post_features = self.post_features_repository.get_post_features(post_id)
        if post_features is None:
            raise PostFeaturesNotFoundException(post_id)

        post_interaction = self.post_interaction_features_repository.get_for_update(post_id)
        if post_interaction is None:
            raise PostInteractionFeaturesNotFoundException(post_id)

        return post_features, post_interaction

    def _get_user_entities(self, user_id: UUID, post_features: PostFeatures, occurred_at):
        user_creator = self._get_user_creator_features_for_update(
            user_id, post_features, occurred_at
        )
        post_tags = self._get_post_tag_features_for_update(
            user_id, post_features, occurred_at
        )
        return user_creator, post_tags

    def _apply_updates(
        self, user_creator, post_tags, post_interaction,
        metric_updates, embedding_weight, occurred_at,
        is_fast_skip, watch_percent
    ):
        if not is_fast_skip:
            old_views = self._capture_old_views(user_creator, post_tags, post_interaction)

        self._apply_common_metrics(
            user_creator, post_tags, post_interaction,
            metric_updates, embedding_weight, occurred_at
        )

        if not is_fast_skip:
            self._apply_valid_view_metrics(
                user_creator, post_tags, post_interaction,
                old_views, watch_percent
            )

    def _apply_common_metrics(self, user_creator, post_tags, post_interaction, metric_updates, embedding_weight, occurred_at):
        user_creator.apply_interaction_updates(metric_updates, occurred_at)
        for post_tag in post_tags:
            post_tag.apply_interaction_updates(metric_updates, occurred_at)
        post_interaction.apply_interaction_updates(metric_updates, occurred_at, embedding_weight)

    def _apply_valid_view_metrics(self, user_creator, post_tags, post_interaction, old_views_per_entity, watch_percent):
        self._apply_view_metrics(user_creator, old_views_per_entity["user_creator"], watch_percent)
        for i, post_tag in enumerate(post_tags):
            self._apply_view_metrics(post_tag, old_views_per_entity["tags"][i], watch_percent)
        self._apply_view_metrics_post_interaction(post_interaction, old_views_per_entity["post_interaction"], watch_percent)

    def _save_entities(self, user_creator, post_tags, post_interaction):
        self.user_creator_features_repository.save(user_creator)
        self.post_tag_features_repository.save_all(post_tags)
        self.post_interaction_features_repository.save(post_interaction)

    @staticmethod
    def _capture_old_views(user_creator_features, post_tags_features, post_interaction_features):
        return {
            "user_creator": (
                user_creator_features.raw_interaction_stats.views,
                user_creator_features.raw_interaction_stats.watch_time_average_percent,
            ),
            "tags": [
                (
                    tag.raw_interaction_stats.views,
                    tag.raw_interaction_stats.watch_time_average_percent,
                )
                for tag in post_tags_features
            ],
            "post_interaction": (
                post_interaction_features.raw_interaction_stats.views,
                post_interaction_features.raw_interaction_stats.watch_time_average_percent,
            ),
        }

    @staticmethod
    def _compute_new_average(old_views: int, old_avg: float, watch_percent: float) -> float:
        return (old_avg * old_views + watch_percent) / (old_views + 1)

    def _apply_view_metrics(self, entity, old_values, watch_percent):
        old_views, old_avg = old_values
        raw_stats = entity.raw_interaction_stats
        decayed_stats = entity.decayed_interaction_stats

        raw_stats.increment(InteractionMetric.VIEWS, 1)
        raw_stats.watch_time_average_percent = self._compute_new_average(old_views, old_avg, watch_percent)

        if raw_stats.impressions > 0:
            decayed_stats.views_engagement = raw_stats.views / raw_stats.impressions

        entity.recalculate_affinity_score()

    def _apply_view_metrics_post_interaction(self, entity, old_values, watch_percent):
        old_views, old_avg = old_values
        raw_stats = entity.raw_interaction_stats
        decayed_stats = entity.decayed_interaction_stats

        raw_stats.increment(InteractionMetric.VIEWS, 1)
        raw_stats.watch_time_average_percent = self._compute_new_average(old_views, old_avg, watch_percent)

        if raw_stats.impressions > 0:
            decayed_stats.views_engagement = raw_stats.views / raw_stats.impressions

    def _get_user_creator_features_for_update(self, user_id, post_features, occurred_at):
        user_creator_features = self.user_creator_features_repository.get_user_creator_features(
            user_id, post_features.creator_id,
        )
        if user_creator_features is None:
            logger.info(
                "No user-creator relation found, inserting empty row: user_id=%s creator_id=%s",
                user_id, post_features.creator_id,
            )
            self.user_creator_features_repository.create_if_absent(
                UserCreatorFeatures.initialize_empty(
                    user_id=user_id,
                    creator_id=post_features.creator_id,
                    occurred_at=occurred_at,
                )
            )

        return self.user_creator_features_repository.get_user_creator_features_for_update(
            user_id, post_features.creator_id,
        )

    def _get_post_tag_features_for_update(self, user_id, post_features, occurred_at):
        existing_tags = {
            f.tag_name
            for f in self.post_tag_features_repository.get_post_tag_features(
                user_id, post_features.tags,
            )
        }
        missing_tags = set(post_features.tags) - existing_tags
        if missing_tags:
            logger.info(
                "Missing tag features, inserting empty rows: user_id=%s tags=%s",
                user_id, missing_tags,
            )
            self.post_tag_features_repository.create_if_absent([
                PostTagFeatures.initialize_empty(
                    user_id=user_id,
                    tag_name=tag_name,
                    occurred_at=occurred_at,
                )
                for tag_name in missing_tags
            ])

        return self.post_tag_features_repository.get_post_tag_features_for_update(
            user_id, post_features.tags,
        )

    def _update_user_semantic_embedding(self, user_id, post_semantic_embedding, weight, source, occurred_at):
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
