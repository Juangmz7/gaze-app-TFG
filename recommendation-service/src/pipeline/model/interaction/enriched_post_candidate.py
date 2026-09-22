from dataclasses import dataclass
from datetime import datetime
from uuid import UUID

from pipeline.enum.post_retrieve_source import PostRetrieveSource


@dataclass
class EnrichedPostCandidate:
    post_id: UUID
    creator_id: UUID
    
    impressions: int
    views: int
    likes: int
    comments: int
    shares: int
    fast_skips: int
    collab_requests: int
    watch_time_average_percent: float

    decayed_impressions: float
    views_engagement: float
    decayed_likes: float
    decayed_comments: float
    decayed_shares: float
    decayed_fast_skips: float
    decayed_collab_requests: float
    last_decay_applied_at: datetime

    follows_creator: bool
    creator_affinity_score: float
    tags_affinity_score: list[float]
    retrieved_source: PostRetrieveSource
    retrieved_source_score: float
    created_at: datetime
