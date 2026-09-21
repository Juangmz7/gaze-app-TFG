import heapq
from uuid import UUID

from pipeline.model.interaction.enriched_post_candidate import EnrichedPostCandidate
from pipeline.config import constants
from pipeline.service.candidate_normalizer import CandidateNormalizer


class PostWeightedRankerService:
    def __init__(self, normalizer: CandidateNormalizer):
        self.normalizer = normalizer

    def get_top_k_posts(self, candidates: list[EnrichedPostCandidate], k: int) -> list[UUID]:
        if not candidates:
            return []
            
        normalized_candidates = self.normalizer.normalize(candidates)
        
        def calculate_global_score(c: EnrichedPostCandidate) -> float:
            score = 0.0
            
            # Raw stats
            score += c.impressions * constants.RANKING_WEIGHT_IMPRESSIONS
            score += c.views * constants.RANKING_WEIGHT_VIEWS
            score += c.likes * constants.RANKING_WEIGHT_LIKES
            score += c.comments * constants.RANKING_WEIGHT_COMMENTS
            score += c.shares * constants.RANKING_WEIGHT_SHARES
            score += c.fast_skips * constants.RANKING_WEIGHT_FAST_SKIPS
            score += c.collab_requests * constants.RANKING_WEIGHT_COLLAB_REQUESTS
            score += c.watch_time_average_percent * constants.RANKING_WEIGHT_WATCH_TIME_AVG_PERCENT
            
            # Decayed stats
            score += c.decayed_impressions * constants.RANKING_WEIGHT_DECAYED_IMPRESSIONS
            score += c.views_engagement * constants.RANKING_WEIGHT_VIEWS_ENGAGEMENT
            score += c.decayed_likes * constants.RANKING_WEIGHT_DECAYED_LIKES
            score += c.decayed_comments * constants.RANKING_WEIGHT_DECAYED_COMMENTS
            score += c.decayed_shares * constants.RANKING_WEIGHT_DECAYED_SHARES
            score += c.decayed_fast_skips * constants.RANKING_WEIGHT_DECAYED_FAST_SKIPS
            score += c.decayed_collab_requests * constants.RANKING_WEIGHT_DECAYED_COLLAB_REQUESTS
            
            # Affinities and Source
            score += (1.0 if c.follows_creator else 0.0) * constants.RANKING_WEIGHT_FOLLOWS_CREATOR
            score += c.creator_afinity_score * constants.RANKING_WEIGHT_CREATOR_AFFINITY
            
            # For lists of tags affinity, we can take the average or max. Let's use max for now, falling back to 0.0
            tags_score = max(c.tags_affinity_score) if c.tags_affinity_score else 0.0
            score += tags_score * constants.RANKING_WEIGHT_TAGS_AFFINITY
            
            score += c.retrieved_source_score * constants.RANKING_WEIGHT_RETRIEVED_SOURCE_SCORE
            
            return score

        # heapq.nlargest is highly optimized for this exact use case (finding top K). 
        # It handles building a min-heap of size K under the hood.
        top_candidates = heapq.nlargest(k, normalized_candidates, key=calculate_global_score)
        
        return [c.post_id for c in top_candidates]