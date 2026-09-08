from datetime import datetime
from typing import Optional
from uuid import UUID

class PostFeatures:
    def __init__(
            self,
            post_id: UUID,
            creator_id: UUID,
            collab_id: Optional[UUID],
            collab_title: Optional[str],
            description: str | None,
            tags: list[str],
            tagged_users_ids: list[str],
            semantic_embedding: list[float],
            created_at: datetime
    ):
        self.post_id = post_id
        self.creator_id = creator_id
        self.collab_id = collab_id
        self.collab_title = collab_title
        self.description = description
        self.tags = tags
        self.tagged_users_ids = tagged_users_ids
        self.semantic_embedding = semantic_embedding
        self.created_at = created_at
