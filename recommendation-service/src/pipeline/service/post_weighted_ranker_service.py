import heapq
from uuid import UUID

from pipeline.model.interaction.enriched_post_candidate import EnrichedPostCandidate
from pipeline.model.ranking.normalized_post_ranking_features import NormalizedPostRankingFeatures
from pipeline.config import constants
from pipeline.service.candidate_normalizer import CandidateNormalizer

class PostWeightedRankerService:
    def __init__(self, normalizer: CandidateNormalizer):
        self.normalizer = normalizer

    def get_top_k_posts(self, candidates: list[EnrichedPostCandidate], k: int) -> list[NormalizedPostRankingFeatures]:
        if not candidates:
            return []
            
        normalized_candidates = self.normalizer.normalize(candidates)

        # heapq.nlargest is highly optimized for finding top K
        top_candidates = heapq.nlargest(k, normalized_candidates, key=self._calculate_global_score)
        
        return top_candidates

    def _calculate_global_score(self, c: NormalizedPostRankingFeatures) -> float:
        score = 0.0
        
        # Raw stats
        score += c.views * constants.RANKING_WEIGHT_VIEWS
        score += c.likes * constants.RANKING_WEIGHT_LIKES
        score += c.comments * constants.RANKING_WEIGHT_COMMENTS
        score += c.shares * constants.RANKING_WEIGHT_SHARES
        score += c.fast_skips * constants.RANKING_WEIGHT_FAST_SKIPS
        score += c.collab_requests * constants.RANKING_WEIGHT_COLLAB_REQUESTS
        score += c.watch_time_average_percent * constants.RANKING_WEIGHT_WATCH_TIME_AVG_PERCENT
        
        # Decayed stats
        score += c.views_engagement * constants.RANKING_WEIGHT_VIEWS_ENGAGEMENT
        score += c.decayed_likes * constants.RANKING_WEIGHT_DECAYED_LIKES
        score += c.decayed_comments * constants.RANKING_WEIGHT_DECAYED_COMMENTS
        score += c.decayed_shares * constants.RANKING_WEIGHT_DECAYED_SHARES
        score += c.decayed_fast_skips * constants.RANKING_WEIGHT_DECAYED_FAST_SKIPS
        score += c.decayed_collab_requests * constants.RANKING_WEIGHT_DECAYED_COLLAB_REQUESTS
        
        # Affinities and Source
        score += c.follows_creator * constants.RANKING_WEIGHT_FOLLOWS_CREATOR
        score += c.creator_affinity_score * constants.RANKING_WEIGHT_CREATOR_AFFINITY
        score += c.tags_affinity_score * constants.RANKING_WEIGHT_TAGS_AFFINITY
        score += c.retrieved_source_score * constants.RANKING_WEIGHT_RETRIEVED_SOURCE_SCORE
        
        # Freshness
        score += c.freshness_score * constants.RANKING_WEIGHT_FRESHNESS
        
        return score
