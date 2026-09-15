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
    ) -> list[Candidate]:
        users_ids = self.colaborative_post_retrieval_repository.get_similar_users(user_id, USER_LIMIT)
        if not users_ids:
            return []

        posts = self.colaborative_post_retrieval_repository.get_posts_ordered_by_user_affinity(users_ids, user_id, POST_LIMIT, POST_LIMIT)

        #TODO? Retry logic if the amount of posts is less than the expected limit

        return [
            Candidate(post_id, PostRetrieveSource.COLLABORATIVE, score)
            for post_id, score in posts
        ]