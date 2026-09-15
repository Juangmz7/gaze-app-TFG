from uuid import UUID

from pipeline.enum.post_retrieve_source import PostRetrieveSource
from pipeline.model.interaction.candidate import Candidate
from pipeline.repository.semantic_post_retrieval_repository import SemanticPostRetrievalRepository


POST_LIMIT = 200

class SemanticPostRetrievalUseCase:
    def __init__(self, semantic_post_retrieval_repository: SemanticPostRetrievalRepository):
        self.semantic_post_retrieval_repository = semantic_post_retrieval_repository

    def retrieve_posts(self, user_id: UUID) -> list[Candidate]:
        posts = self.semantic_post_retrieval_repository.get_similar_posts(user_id, POST_LIMIT)
        #TODO? Retry logic if the amount of posts is less than the expected limit

        return [
            Candidate(post_id, PostRetrieveSource.SEMANTIC, score)
            for post_id, score in posts
        ]
