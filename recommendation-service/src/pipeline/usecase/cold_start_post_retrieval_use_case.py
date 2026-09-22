from uuid import UUID

from pipeline.enum.post_retrieve_source import PostRetrieveSource
from pipeline.model.interaction.candidate import Candidate
from pipeline.repository.explorative_post_retrieval_repository import ExplorativePostRetrievalRepository
from pipeline.service.post_candidate_enricher_service import PostCandidateEnricherService
from pipeline.service.candidate_normalizer import CandidateNormalizer
from pipeline.model.ranking.normalized_post_ranking_features import NormalizedPostRankingFeatures

class ColdStartPostRetrievalUseCase:
    def __init__(
        self,
        explorative_post_retrieval_repository: ExplorativePostRetrievalRepository,
        candidate_enricher_service: PostCandidateEnricherService,
        candidate_normalizer: CandidateNormalizer,
    ):
        self.repository = explorative_post_retrieval_repository
        self.enricher = candidate_enricher_service
        self.normalizer = candidate_normalizer

    def retrieve_and_prepare_cold_start_posts(
        self,
        user_id: UUID,
        limit: int
    ) -> list[NormalizedPostRankingFeatures]:
        if limit <= 0:
            limit = 1
            
        posts = self.repository.get_cold_start_posts(user_id, limit)
        if not posts:
            return []
            
        candidates = [
            Candidate(post_id, PostRetrieveSource.COLD_START, score)
            for post_id, score in posts
        ]
        
        enriched = self.enricher.enrich(user_id, candidates)
        if not enriched:
            return []
            
        normalized = self.normalizer.normalize(enriched)
        return normalized
