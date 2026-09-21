from uuid import UUID

from pipeline.enum.post_retrieve_source import PostRetrieveSource
from pipeline.model.interaction.candidate import Candidate
from pipeline.repository.colaborative_post_retrieval_repository import CollaborativePostRetrievalRepository

USER_LIMIT = 100
POST_LIMIT = 20

class CollaborativePostRetrievalUseCase:
    def __init__(self, colaborative_post_retrieval_repository: CollaborativePostRetrievalRepository):
        self.colaborative_post_retrieval_repository = colaborative_post_retrieval_repository

    def retrieve_posts(
            self,
            user_id: UUID,
            limit_multiplier: float = 1.0
    ) -> list[Candidate]:
        users_ids = self.colaborative_post_retrieval_repository.get_similar_users(user_id, int(USER_LIMIT * limit_multiplier))
        if not users_ids:
            return []

        posts = self.colaborative_post_retrieval_repository.get_posts_ordered_by_user_affinity(
            users_ids, user_id, int(POST_LIMIT * limit_multiplier), int(POST_LIMIT * limit_multiplier)
        )

        return [
            Candidate(post_id, PostRetrieveSource.COLLABORATIVE, score)
            for post_id, score in posts
        ]
