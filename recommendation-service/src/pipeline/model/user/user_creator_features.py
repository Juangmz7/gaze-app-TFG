from datetime import datetime
from uuid import UUID

from pipeline.model.interaction.decayed_interaction_stats import DecayedInteractionStats
from pipeline.model.interaction.raw_interaction_stats import RawInteractionStats
from pipeline.model.interaction.affinity_score_processor import AffinityScoreProcessor
from pipeline.model.interaction.interaction_metric_update import InteractionMetricUpdate
from shared.helpers import decay

class UserCreatorFeatures:
    def __init__(
            self, user_id: UUID,
            creator_id: UUID,
            raw_interaction_stats: RawInteractionStats,
            decayed_interaction_stats: DecayedInteractionStats,
            last_updated_at: datetime
    ):
        self.user_id = user_id
        self.creator_id = creator_id
        self.raw_interaction_stats = raw_interaction_stats
        self.decayed_interaction_stats = decayed_interaction_stats
        self.affinity_score = self.calculate_affinity_score()
        self.last_updated_at = last_updated_at

    def calculate_affinity_score(self) -> float:
        affinity_score_processor = AffinityScoreProcessor(self.decayed_interaction_stats)
        return affinity_score_processor.calculate_affinity_score()

    def recalculate_affinity_score(self) -> None:
        self.affinity_score = self.calculate_affinity_score()

    def apply_interaction_updates(
            self,
            updates: list[InteractionMetricUpdate],
            occurred_at: datetime,
    ) -> None:
        for update in updates:
            if update.raw_delta is not None:
                self.raw_interaction_stats.increment(update.metric, update.raw_delta)

        factor = decay(self.last_updated_at, occurred_at)
        self.decayed_interaction_stats.apply_decay(factor)
        for update in updates:
            if update.decayed_delta is not None:
                self.decayed_interaction_stats.increment(update.metric, update.decayed_delta)

        self.recalculate_affinity_score()
        self.last_updated_at = occurred_at
