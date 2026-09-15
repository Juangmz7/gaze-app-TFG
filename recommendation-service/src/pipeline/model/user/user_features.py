
from datetime import datetime
from uuid import UUID

from shared.helpers import decay, l2_normalize_vector

class UserFeatures:
    def __init__(
            self,
            user_id: UUID,
            semantic_embedding: list[float],
            last_updated_at: datetime,
            has_semantic_signal: bool = False,
    ):
        self.user_id = user_id
        self.semantic_embedding = semantic_embedding
        self.last_updated_at = last_updated_at
        self.has_semantic_signal = has_semantic_signal

    def apply_semantic_interaction(
            self,
            post_semantic_embedding: list[float],
            embedding_weight: float,
            source_weight: float,
            occurred_at: datetime,
    ) -> None:
        factor = decay(self.last_updated_at, occurred_at)
        decayed_user_embedding = [
            value * factor
            for value in self.semantic_embedding
        ]
        weighted_post_embedding = [
            value * embedding_weight * source_weight
            for value in post_semantic_embedding
        ]

        self.semantic_embedding = l2_normalize_vector([
            user_value + post_value
            for user_value, post_value in zip(
                decayed_user_embedding,
                weighted_post_embedding,
                strict=True,
            )
        ])
        self.last_updated_at = occurred_at
        self.has_semantic_signal = True
