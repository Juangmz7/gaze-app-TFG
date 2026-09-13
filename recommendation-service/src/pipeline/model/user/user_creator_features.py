from datetime import datetime
from uuid import UUID

from pipeline.entity.interaction.interaction_features import InteractionFeatures
from pipeline.model.interaction.decayed_interaction_stats import DecayedInteractionStats
from pipeline.model.interaction.raw_interaction_stats import RawInteractionStats


class UserCreatorFeatures(InteractionFeatures):
    def __init__(
        self,
        user_id: UUID,
        creator_id: UUID,
        raw_interaction_stats: RawInteractionStats,
        decayed_interaction_stats: DecayedInteractionStats,
        last_updated_at: datetime,
    ):
        super().__init__(
            raw_interaction_stats,
            decayed_interaction_stats,
            last_updated_at,
        )

        self.user_id = user_id
        self.creator_id = creator_id

    @classmethod
    def initialize_empty(
        cls,
        user_id: UUID,
        creator_id: UUID,
        occurred_at: datetime,
    ) -> "UserCreatorFeatures":
        return cls(
            user_id=user_id,
            creator_id=creator_id,
            raw_interaction_stats=RawInteractionStats.empty(),
            decayed_interaction_stats=DecayedInteractionStats.empty(),
            last_updated_at=occurred_at,
        )