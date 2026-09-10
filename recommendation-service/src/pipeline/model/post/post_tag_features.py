import datetime
from uuid import UUID

from pipeline.entity.interaction.interaction_features import InteractionFeatures
from pipeline.model.interaction.decayed_interaction_stats import DecayedInteractionStats
from pipeline.model.interaction.raw_interaction_stats import RawInteractionStats


class PostTagFeatures(InteractionFeatures):
    def __init__(
        self,
        user_id: UUID,
        tag_name: str,
        raw_interaction_stats: RawInteractionStats,
        decayed_interaction_stats: DecayedInteractionStats,
        last_updated_at: datetime.datetime,
    ):
        super().__init__(
            raw_interaction_stats,
            decayed_interaction_stats,
            last_updated_at,
        )

        self.user_id = user_id
        self.tag_name = tag_name

    @classmethod
    def initialize_empty(
        cls,
        user_id: UUID,
        tag_name: str,
        occurred_at: datetime.datetime,
    ) -> "PostTagFeatures":
        return cls(
            user_id=user_id,
            tag_name=tag_name,
            raw_interaction_stats=RawInteractionStats.empty(),
            decayed_interaction_stats=DecayedInteractionStats.empty(),
            last_updated_at=occurred_at,
        )