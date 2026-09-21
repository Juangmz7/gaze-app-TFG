from uuid import UUID

class PostRerankerService:
    def rerank(self, user_id: UUID, post_ids: list[UUID]) -> list[UUID]:
        if not post_ids:
            return []
        pass
        return post_ids
