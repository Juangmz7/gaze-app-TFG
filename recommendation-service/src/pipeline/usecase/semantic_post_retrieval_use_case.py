
from sqlalchemy import UUID

from pipeline.repository.semantic_post_retrieval_repository import SemanticPostRetrievalRepository


POST_LIMIT = 200

class SemanticPostRetrievalUseCase:
    def __init__(self, semantic_post_retrieval_repository: SemanticPostRetrievalRepository):
        self.semantic_post_retrieval_repository = semantic_post_retrieval_repository

    def retrieve_posts(self, user_id: UUID) -> list[UUID]:
        posts_ids = self.semantic_post_retrieval_repository.get_similar_posts(user_id, POST_LIMIT)
        #TODO? Retry logic if the amount of posts is less than the expected limit

        return posts_ids
