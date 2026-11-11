import logging
from uuid import UUID

from pipeline.enum.post_retrieve_source import PostRetrieveSource
from pipeline.model.interaction.candidate import Candidate
from pipeline.repository.colaborative_post_retrieval_repository import CollaborativePostRetrievalRepository

USER_LIMIT = 100
POST_LIMIT = 20

logger = logging.getLogger(__name__)

class CollaborativePostRetrievalUseCase:
    def __init__(self, colaborative_post_retrieval_repository: CollaborativePostRetrievalRepository):
        self.colaborative_post_retrieval_repository = colaborative_post_retrieval_repository

    def has_semantic_signal(self, user_id: UUID) -> bool:
        """False for a user without signal and for one this service does not know yet."""
        return self.colaborative_post_retrieval_repository.has_semantic_signal(user_id)

    def retrieve_posts(
            self,
            user_id: UUID,
            limit_multiplier: float = 1.0
    ) -> list[Candidate]:
        users_ids = self.colaborative_post_retrieval_repository.get_similar_users(user_id, int(USER_LIMIT * limit_multiplier))
        if not users_ids:
            logger.debug("Collaborative retrieval found no similar users: user_id=%s", user_id)
            return []

        posts = self.colaborative_post_retrieval_repository.get_posts_ordered_by_user_affinity(
            users_ids, user_id, int(POST_LIMIT * limit_multiplier), int(POST_LIMIT * limit_multiplier)
        )

        logger.debug("Collaborative retrieval: user_id=%s similar_users=%s posts=%s", user_id, len(users_ids), len(posts))

        return [
            Candidate(post_id, PostRetrieveSource.COLLABORATIVE, score)
            for post_id, score in posts
        ]
