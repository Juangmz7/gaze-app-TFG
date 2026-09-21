import logging
from uuid import UUID

logger = logging.getLogger(__name__)

class PostRerankerService:
    def rerank(self, user_id: UUID, post_ids: list[UUID]) -> list[UUID]:
        if not post_ids:
            logger.warning(f"Reranker received empty post list for user {user_id}")
            return []
            
        logger.debug(f"Passing through {len(post_ids)} posts in reranker for user {user_id}")
        # pass logic for now
        return post_ids
