import math
from datetime import datetime, timezone

from pipeline.model.interaction.enriched_post_candidate import EnrichedPostCandidate
from pipeline.model.ranking.normalized_post_ranking_features import NormalizedPostRankingFeatures
from pipeline.service.candidate_normalizer import CandidateNormalizer
from pipeline.config import constants
from shared.helpers import decay

class DeterministicCandidateNormalizer(CandidateNormalizer):
    
    def normalize(self, candidates: list[EnrichedPostCandidate]) -> list[NormalizedPostRankingFeatures]:
        if not candidates:
            return []

        now = datetime.now(timezone.utc)
        
        return [self._normalize_candidate(c, now) for c in candidates]

    def _normalize_candidate(self, c: EnrichedPostCandidate, now: datetime) -> NormalizedPostRankingFeatures:
        views = self._normalize_count(c.views, constants.RANKING_VIEWS_SATURATION_POINT)
        likes = self._normalize_count(c.likes, constants.RANKING_LIKES_SATURATION_POINT)
        comments = self._normalize_count(c.comments, constants.RANKING_COMMENTS_SATURATION_POINT)
        shares = self._normalize_count(c.shares, constants.RANKING_SHARES_SATURATION_POINT)
        fast_skips = self._normalize_count(c.fast_skips, constants.RANKING_FAST_SKIPS_SATURATION_POINT)
        collab_requests = self._normalize_count(c.collab_requests, constants.RANKING_COLLAB_REQUESTS_SATURATION_POINT)

        views_engagement = self._normalize_count(c.views_engagement, constants.RANKING_VIEWS_SATURATION_POINT)
        decayed_likes = self._normalize_count(c.decayed_likes, constants.RANKING_LIKES_SATURATION_POINT)
        decayed_comments = self._normalize_count(c.decayed_comments, constants.RANKING_COMMENTS_SATURATION_POINT)
        decayed_shares = self._normalize_count(c.decayed_shares, constants.RANKING_SHARES_SATURATION_POINT)
        decayed_fast_skips = self._normalize_count(c.decayed_fast_skips, constants.RANKING_FAST_SKIPS_SATURATION_POINT)
        decayed_collab_requests = self._normalize_count(c.decayed_collab_requests, constants.RANKING_COLLAB_REQUESTS_SATURATION_POINT)

        watch_time_percent = self._normalize_percentage(c.watch_time_average_percent)
        follows_creator = 1.0 if c.follows_creator else 0.0

        tags_affinity = self._max_affinity(c.tags_affinity_score)

        freshness = decay(c.created_at, now)

        return NormalizedPostRankingFeatures(
            post_id=c.post_id,
            views=views,
            likes=likes,
            comments=comments,
            shares=shares,
            fast_skips=fast_skips,
            collab_requests=collab_requests,
            watch_time_average_percent=watch_time_percent,
            views_engagement=views_engagement,
            decayed_likes=decayed_likes,
            decayed_comments=decayed_comments,
            decayed_shares=decayed_shares,
            decayed_fast_skips=decayed_fast_skips,
            decayed_collab_requests=decayed_collab_requests,
            follows_creator=follows_creator,
            creator_affinity_score=c.creator_affinity_score,
            tags_affinity_score=tags_affinity,
            freshness_score=freshness,
            retrieved_source=c.retrieved_source,
            retrieved_source_score=c.retrieved_source_score
        )
        
    def _normalize_count(self, value: float | int, saturation_point: float) -> float:
        """
        Applies log1p scaling to a count metric.
        - Uses saturation_point as the point where the feature reaches 1.0.
        - Values higher than saturation_point are saturated (capped) at 1.0.
        - Negative values are treated as 0.0.
        """
        if saturation_point <= 0:
            raise ValueError("saturation_point must be greater than 0")
            
        val = max(0.0, float(value))
        scaled = math.log1p(val) / math.log1p(saturation_point)
        return min(scaled, 1.0)
        
    def _normalize_percentage(self, value: float) -> float:
        return max(0.0, min(value / 100.0, 1.0))
        
    def _max_affinity(self, values: list[float]) -> float:
        return max(values) if values else 0.0
