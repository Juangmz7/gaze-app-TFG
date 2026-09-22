from dataclasses import dataclass
from uuid import UUID
from pipeline.enum.post_retrieve_source import PostRetrieveSource

@dataclass
class NormalizedPostRankingFeatures:
    post_id: UUID
    creator_id: UUID
    
    views: float
    likes: float
    comments: float
    shares: float
    fast_skips: float
    collab_requests: float
    watch_time_average_percent: float

    views_engagement: float
    decayed_likes: float
    decayed_comments: float
    decayed_shares: float
    decayed_fast_skips: float
    decayed_collab_requests: float

    follows_creator: float
    creator_affinity_score: float
    tags_affinity_score: float

    freshness_score: float

    retrieved_source: PostRetrieveSource
    retrieved_source_score: float
