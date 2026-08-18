
from datetime import datetime
from uuid import UUID

from pipeline.model.interaction.affinity_score_processor import AffinityScoreProcessor
from pipeline.model.interaction.decayed_interaction_stats import DecayedInteractionStats
from pipeline.model.interaction.raw_interaction_stats import RawInteractionStats

class PostTagFeatures:
    def __init__(
            self,
            user_id: UUID,
            tag_name: str,
            raw_interaction_stats: RawInteractionStats,
            decayed_interaction_stats: DecayedInteractionStats,
            last_updated_at: datetime
    ):
        self.user_id = user_id
        self.tag_name = tag_name
        self.raw_interaction_stats = raw_interaction_stats
        self.decayed_interaction_stats = decayed_interaction_stats
        self.affinity_score = self.calculate_affinity_score(self.decayed_interaction_stats)
        self.last_updated_at = last_updated_at

    def calculate_affinity_score(self) -> float:
        affinity_score_processor = AffinityScoreProcessor(self.decayed_interaction_stats)
        return affinity_score_processor.calculate_affinity_score()

