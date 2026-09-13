import datetime

from pipeline.model.interaction.affinity_score_processor import AffinityScoreProcessor
from pipeline.model.interaction.decayed_interaction_stats import DecayedInteractionStats
from pipeline.model.interaction.interaction_metric_update import InteractionMetricUpdate
from pipeline.model.interaction.raw_interaction_stats import RawInteractionStats


class InteractionFeatures:
    def __init__(
        self,
        raw_interaction_stats: RawInteractionStats,
        decayed_interaction_stats: DecayedInteractionStats,
        last_updated_at: datetime,
    ):
        self.raw_interaction_stats = raw_interaction_stats
        self.decayed_interaction_stats = decayed_interaction_stats
        self.last_updated_at = last_updated_at
        self.affinity_score = self.calculate_affinity_score()

    def calculate_affinity_score(self) -> float:
        processor = AffinityScoreProcessor(
            self.decayed_interaction_stats
        )
        return processor.calculate_affinity_score()

    def recalculate_affinity_score(self) -> None:
        self.affinity_score = self.calculate_affinity_score()

    def apply_interaction_updates(
        self,
        updates: list[InteractionMetricUpdate],
        occurred_at: datetime,
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

        self.recalculate_affinity_score()
        self.last_updated_at = occurred_at