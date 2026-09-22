import pytest
from uuid import uuid4
from datetime import datetime, timezone

from pipeline.model.interaction.enriched_post_candidate import EnrichedPostCandidate
from pipeline.service.post_weighted_ranker_service import PostWeightedRankerService
from pipeline.service.deterministic_candidate_normalizer import DeterministicCandidateNormalizer
from pipeline.enum.post_retrieve_source import PostRetrieveSource
from pipeline.config import constants

def test_ranker_service_returns_top_k():
    normalizer = DeterministicCandidateNormalizer()
    ranker = PostWeightedRankerService(normalizer=normalizer)
    
    now = datetime.now(timezone.utc)
    
    p1 = uuid4()
    p2 = uuid4()
    p3 = uuid4()
    
    # Configure weights so views is the only thing that matters
    constants.RANKING_WEIGHT_VIEWS = 1.0
    constants.RANKING_WEIGHT_LIKES = 0.0
    constants.RANKING_WEIGHT_COMMENTS = 0.0
    constants.RANKING_WEIGHT_SHARES = 0.0
    constants.RANKING_WEIGHT_FAST_SKIPS = 0.0
    constants.RANKING_WEIGHT_COLLAB_REQUESTS = 0.0
    constants.RANKING_WEIGHT_WATCH_TIME_AVG_PERCENT = 0.0
    constants.RANKING_WEIGHT_VIEWS_ENGAGEMENT = 0.0
    constants.RANKING_WEIGHT_DECAYED_LIKES = 0.0
    constants.RANKING_WEIGHT_DECAYED_COMMENTS = 0.0
    constants.RANKING_WEIGHT_DECAYED_SHARES = 0.0
    constants.RANKING_WEIGHT_DECAYED_FAST_SKIPS = 0.0
    constants.RANKING_WEIGHT_DECAYED_COLLAB_REQUESTS = 0.0
    constants.RANKING_WEIGHT_FOLLOWS_CREATOR = 0.0
    constants.RANKING_WEIGHT_CREATOR_AFFINITY = 0.0
    constants.RANKING_WEIGHT_TAGS_AFFINITY = 0.0
    constants.RANKING_WEIGHT_RETRIEVED_SOURCE_SCORE = 0.0
    constants.RANKING_WEIGHT_FRESHNESS = 0.0
    
    c1 = EnrichedPostCandidate(
        post_id=p1, creator_id=uuid4(), impressions=0, views=10, likes=0, comments=0, shares=0,
        fast_skips=0, collab_requests=0, watch_time_average_percent=0.0,
        decayed_impressions=0.0, views_engagement=0.0, decayed_likes=0.0,
        decayed_comments=0.0, decayed_shares=0.0, decayed_fast_skips=0.0,
        decayed_collab_requests=0.0, last_decay_applied_at=now,
        follows_creator=False, creator_affinity_score=0.0, tags_affinity_score=[],
        retrieved_source=PostRetrieveSource.POPULAR, retrieved_source_score=0.0,
        created_at=now
    )
    
    c2 = EnrichedPostCandidate(
        post_id=p2, creator_id=uuid4(), impressions=0, views=1000, likes=0, comments=0, shares=0,
        fast_skips=0, collab_requests=0, watch_time_average_percent=0.0,
        decayed_impressions=0.0, views_engagement=0.0, decayed_likes=0.0,
        decayed_comments=0.0, decayed_shares=0.0, decayed_fast_skips=0.0,
        decayed_collab_requests=0.0, last_decay_applied_at=now,
        follows_creator=False, creator_affinity_score=0.0, tags_affinity_score=[],
        retrieved_source=PostRetrieveSource.POPULAR, retrieved_source_score=0.0,
        created_at=now
    )
    
    c3 = EnrichedPostCandidate(
        post_id=p3, creator_id=uuid4(), impressions=0, views=100, likes=0, comments=0, shares=0,
        fast_skips=0, collab_requests=0, watch_time_average_percent=0.0,
        decayed_impressions=0.0, views_engagement=0.0, decayed_likes=0.0,
        decayed_comments=0.0, decayed_shares=0.0, decayed_fast_skips=0.0,
        decayed_collab_requests=0.0, last_decay_applied_at=now,
        follows_creator=False, creator_affinity_score=0.0, tags_affinity_score=[],
        retrieved_source=PostRetrieveSource.POPULAR, retrieved_source_score=0.0,
        created_at=now
    )
    
    result = ranker.get_top_k_posts([c1, c2, c3], 2)
    assert [r.post_id for r in result] == [p2, p3]

def test_negative_fast_skips_handled_by_ranker():
    normalizer = DeterministicCandidateNormalizer()
    ranker = PostWeightedRankerService(normalizer=normalizer)
    
    now = datetime.now(timezone.utc)
    p1 = uuid4()
    p2 = uuid4()
    
    # Configure weights so views is positive and fast skips is negative
    constants.RANKING_WEIGHT_VIEWS = 1.0
    constants.RANKING_WEIGHT_FAST_SKIPS = -1.0
    constants.RANKING_WEIGHT_LIKES = 0.0
    constants.RANKING_WEIGHT_COMMENTS = 0.0
    constants.RANKING_WEIGHT_SHARES = 0.0
    constants.RANKING_WEIGHT_COLLAB_REQUESTS = 0.0
    constants.RANKING_WEIGHT_WATCH_TIME_AVG_PERCENT = 0.0
    constants.RANKING_WEIGHT_VIEWS_ENGAGEMENT = 0.0
    constants.RANKING_WEIGHT_DECAYED_LIKES = 0.0
    constants.RANKING_WEIGHT_DECAYED_COMMENTS = 0.0
    constants.RANKING_WEIGHT_DECAYED_SHARES = 0.0
    constants.RANKING_WEIGHT_DECAYED_FAST_SKIPS = 0.0
    constants.RANKING_WEIGHT_DECAYED_COLLAB_REQUESTS = 0.0
    constants.RANKING_WEIGHT_FOLLOWS_CREATOR = 0.0
    constants.RANKING_WEIGHT_CREATOR_AFFINITY = 0.0
    constants.RANKING_WEIGHT_TAGS_AFFINITY = 0.0
    constants.RANKING_WEIGHT_RETRIEVED_SOURCE_SCORE = 0.0
    constants.RANKING_WEIGHT_FRESHNESS = 0.0

    # c1 and c2 have the same views, but c2 has fast skips. So c1 should be better.
    c1 = EnrichedPostCandidate(
        post_id=p1, creator_id=uuid4(), impressions=0, views=10, likes=0, comments=0, shares=0,
        fast_skips=0, collab_requests=0, watch_time_average_percent=0.0,
        decayed_impressions=0.0, views_engagement=0.0, decayed_likes=0.0,
        decayed_comments=0.0, decayed_shares=0.0, decayed_fast_skips=0.0,
        decayed_collab_requests=0.0, last_decay_applied_at=now,
        follows_creator=False, creator_affinity_score=0.0, tags_affinity_score=[],
        retrieved_source=PostRetrieveSource.POPULAR, retrieved_source_score=0.0,
        created_at=now
    )
    
    c2 = EnrichedPostCandidate(
        post_id=p2, creator_id=uuid4(), impressions=0, views=10, likes=0, comments=0, shares=0,
        fast_skips=1000, collab_requests=0, watch_time_average_percent=0.0,
        decayed_impressions=0.0, views_engagement=0.0, decayed_likes=0.0,
        decayed_comments=0.0, decayed_shares=0.0, decayed_fast_skips=0.0,
        decayed_collab_requests=0.0, last_decay_applied_at=now,
        follows_creator=False, creator_affinity_score=0.0, tags_affinity_score=[],
        retrieved_source=PostRetrieveSource.POPULAR, retrieved_source_score=0.0,
        created_at=now
    )
    
    result = ranker.get_top_k_posts([c1, c2], 2)
    assert [r.post_id for r in result] == [p1, p2]
