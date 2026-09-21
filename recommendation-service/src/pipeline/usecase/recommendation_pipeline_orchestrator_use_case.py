from uuid import UUID

from pipeline.usecase.collaborative_post_retrieval_use_case import CollaborativePostRetrievalUseCase
from pipeline.usecase.semantic_post_retrieval_use_case import SemanticPostRetrievalUseCase
from pipeline.usecase.explorative_post_retrieval_use_case import ExplorativePostRetrievalUsecase
from pipeline.service.post_candidate_enricher_service import PostCandidateEnricherService
from pipeline.service.post_weighted_ranker_service import PostWeightedRankerService
from pipeline.service.post_reranker_service import PostRerankerService

class RecommendationPipelineOrchestratorUseCase:
    def __init__(
        self,
        collaborative_retrieval_usecase: CollaborativePostRetrievalUseCase,
        semantic_retrieval_usecase: SemanticPostRetrievalUseCase,
        explorative_retrieval_usecase: ExplorativePostRetrievalUsecase,
        candidate_enricher_service: PostCandidateEnricherService,
        post_weighted_ranker_service: PostWeightedRankerService,
        post_reranker_service: PostRerankerService,
    ):
        self.collaborative_retrieval_usecase = collaborative_retrieval_usecase
        self.semantic_retrieval_usecase = semantic_retrieval_usecase
        self.explorative_retrieval_usecase = explorative_retrieval_usecase
        self.candidate_enricher_service = candidate_enricher_service
        self.post_weighted_ranker_service = post_weighted_ranker_service
        self.post_reranker_service = post_reranker_service

    def recommend(self, user_id: UUID) -> list[UUID]:
        candidates = []
        
        candidates.extend(self.collaborative_retrieval_usecase.retrieve_posts(user_id))
        candidates.extend(self.semantic_retrieval_usecase.retrieve_posts(user_id))
        candidates.extend(self.explorative_retrieval_usecase.retrieve_posts(user_id))
        
        if not candidates:
            return []
            
        enriched_candidates = self.candidate_enricher_service.enrich(user_id, candidates)
        if not enriched_candidates:
            return []
            
        top_k_post_ids = self.post_weighted_ranker_service.get_top_k_posts(enriched_candidates, 30)
        if not top_k_post_ids:
            return []
            
        reranked_post_ids = self.post_reranker_service.rerank(user_id, top_k_post_ids)
        
        return reranked_post_ids
