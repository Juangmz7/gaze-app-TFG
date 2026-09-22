import logging
from uuid import UUID

from pipeline.usecase.cold_start_post_retrieval_use_case import ColdStartPostRetrievalUseCase
import math
from pipeline.model.interaction.candidate import Candidate
from pipeline.usecase.collaborative_post_retrieval_use_case import CollaborativePostRetrievalUseCase
from pipeline.usecase.semantic_post_retrieval_use_case import SemanticPostRetrievalUseCase
from pipeline.usecase.explorative_post_retrieval_use_case import ExplorativePostRetrievalUsecase
from pipeline.service.post_candidate_enricher_service import PostCandidateEnricherService
from pipeline.service.post_weighted_ranker_service import PostWeightedRankerService
from pipeline.service.post_reranker_service import PostRerankerService

TARGET_RECOMMENDED_POSTS = 28
MAX_RETRIEVAL_RETRIES = 3
RETRIEVAL_MULTIPLIER_STEP = 0.5
COLD_START_MULTIPLIER = 0.05


logger = logging.getLogger(__name__)

class RecommendationPipelineOrchestratorUseCase:
    def __init__(
        self,
        collaborative_retrieval_usecase: CollaborativePostRetrievalUseCase,
        semantic_retrieval_usecase: SemanticPostRetrievalUseCase,
        explorative_retrieval_usecase: ExplorativePostRetrievalUsecase,
        candidate_enricher_service: PostCandidateEnricherService,
        post_weighted_ranker_service: PostWeightedRankerService,
        post_reranker_service: PostRerankerService,
        cold_start_usecase: ColdStartPostRetrievalUseCase,
    ):
        self.collaborative_retrieval_usecase = collaborative_retrieval_usecase
        self.semantic_retrieval_usecase = semantic_retrieval_usecase
        self.explorative_retrieval_usecase = explorative_retrieval_usecase
        self.candidate_enricher_service = candidate_enricher_service
        self.post_weighted_ranker_service = post_weighted_ranker_service
        self.post_reranker_service = post_reranker_service
        self.cold_start_usecase = cold_start_usecase

    def recommend(self, user_id: UUID) -> list[UUID]:
        logger.info(f"Starting recommendation pipeline for user: {user_id}")
        
        candidates = self._retrieve_and_deduplicate(user_id)
        
        if not candidates:
            logger.warning(f"No candidates retrieved for user {user_id}. Aborting pipeline.")
            return []
            
        logger.debug(f"Proceeding to enrich {len(candidates)} candidates for user {user_id}.")
        enriched_candidates = self.candidate_enricher_service.enrich(user_id, candidates)
        
        if not enriched_candidates:
            logger.warning(f"Enricher returned empty candidates for user {user_id}. Aborting pipeline.")
            return []
            
        logger.debug(f"Proceeding to rank {len(enriched_candidates)} enriched candidates for user {user_id}.")
        top_k_normalized_posts = self.post_weighted_ranker_service.get_top_k_posts(enriched_candidates, TARGET_RECOMMENDED_POSTS)
        
        if not top_k_normalized_posts:
            logger.warning(f"Ranker returned empty posts for user {user_id}. Aborting pipeline.")
            return []
            
        cold_start_limit = max(1, math.ceil(TARGET_RECOMMENDED_POSTS * COLD_START_MULTIPLIER))
        logger.debug(f"Fetching {cold_start_limit} cold start posts for user {user_id}.")
        
        cold_start_normalized_posts = self.cold_start_usecase.retrieve_and_prepare_cold_start_posts(user_id, cold_start_limit)
        
        # Combine the lists
        combined_posts = top_k_normalized_posts + cold_start_normalized_posts
            
        logger.debug(f"Proceeding to rerank {len(combined_posts)} combined top posts for user {user_id}.")
        reranked_post_ids = self.post_reranker_service.rerank(user_id, combined_posts)
        
        logger.info(f"Successfully generated {len(reranked_post_ids)} recommendations for user {user_id}.")
        return reranked_post_ids

    def _retrieve_and_deduplicate(self, user_id: UUID) -> list[Candidate]:
        multiplier = 1.0
        
        for attempt in range(1, MAX_RETRIEVAL_RETRIES + 1):
            logger.debug(f"Retrieval attempt {attempt}/{MAX_RETRIEVAL_RETRIES} for user {user_id} with multiplier {multiplier}.")
            
            candidates = []
            candidates.extend(self.collaborative_retrieval_usecase.retrieve_posts(user_id, multiplier))
            candidates.extend(self.semantic_retrieval_usecase.retrieve_posts(user_id, multiplier))
            candidates.extend(self.explorative_retrieval_usecase.retrieve_posts(user_id, multiplier))
            
            seen_ids = set()
            deduplicated = []
            for c in candidates:
                if c.post_id not in seen_ids:
                    seen_ids.add(c.post_id)
                    deduplicated.append(c)
                    
            logger.debug(f"Retrieved {len(candidates)} raw candidates, deduplicated to {len(deduplicated)} for user {user_id}.")
            
            if len(deduplicated) >= TARGET_RECOMMENDED_POSTS:
                logger.info(f"Target of {TARGET_RECOMMENDED_POSTS} posts reached ({len(deduplicated)} found) for user {user_id}.")
                return deduplicated
                
            logger.warning(f"Insufficient candidates ({len(deduplicated)} < {TARGET_RECOMMENDED_POSTS}) for user {user_id}. Retrying...")
            multiplier += RETRIEVAL_MULTIPLIER_STEP
            
        logger.warning(f"Max retrieval retries reached for user {user_id}. Returning {len(deduplicated)} candidates.")
        return deduplicated
