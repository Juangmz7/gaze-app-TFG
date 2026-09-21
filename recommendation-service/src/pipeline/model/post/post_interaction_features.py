from datetime import datetime
from shared.helpers import decay

from uuid import UUID

from pipeline.model.interaction.decayed_interaction_stats import DecayedInteractionStats
from pipeline.model.interaction.interaction_metric_update import InteractionMetricUpdate
from pipeline.model.interaction.raw_interaction_stats import RawInteractionStats


class PostInteractionFeatures:
    def __init__(
            self,
            post_id: UUID,
            raw_interaction_stats: RawInteractionStats,
            decayed_interaction_stats: DecayedInteractionStats,
            last_updated_at: datetime,
            decayed_engagement_score: float = 0.0,
    ):
        self.post_id = post_id
        self.raw_interaction_stats = raw_interaction_stats
        self.decayed_interaction_stats = decayed_interaction_stats
        self.last_updated_at = last_updated_at
        self.decayed_engagement_score = decayed_engagement_score

    def apply_interaction_updates(
        self,
        updates: list[InteractionMetricUpdate],
        occurred_at: datetime,
        weight: float,
    ) -> None:
        self.decayed_interaction_stats.increment(
            updates,
            self.last_updated_at,
            occurred_at,
        )

        for update in updates:
            if update.raw_delta is not None:
                self.raw_interaction_stats.increment(
                    update.metric,
                    update.raw_delta,
                )


        decay_factor = decay(self.last_updated_at, occurred_at)
        self.decayed_engagement_score = self.decayed_engagement_score * decay_factor + weight

        self.last_updated_at = occurred_at

