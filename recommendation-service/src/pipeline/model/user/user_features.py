
from datetime import datetime
from uuid import UUID

class UserFeatures:
    def __init__(
            self,
            user_id: UUID,
            semantic_embedding: list[float],
            last_updated_at: datetime
    ):
        self.user_id = user_id
        self.semantic_embedding = semantic_embedding
        self.last_updated_at = last_updated_at