from datetime import datetime
from uuid import UUID

class PostFeatures:
    def __init__(
            self,
            post_id: UUID,
            creator_id: UUID,
            description: str | None,
            tags: list[str] | None,
            tagged_users_ids: list[UUID],
            semantic_embedding: list[float],
            created_at: datetime
    ):
        self.post_id = post_id
        self.creator_id = creator_id
        self.description = description,
        self.tags = tags
        self.tagged_users_ids = tagged_users_ids
        self.semantic_embedding = semantic_embedding
        self.created_at = created_at