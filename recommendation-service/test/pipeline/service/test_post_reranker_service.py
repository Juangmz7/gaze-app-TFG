import pytest
from uuid import uuid4

from pipeline.service.post_reranker_service import PostRerankerService
from pipeline.model.ranking.normalized_post_ranking_features import NormalizedPostRankingFeatures
from pipeline.enum.post_retrieve_source import PostRetrieveSource

pytestmark = pytest.mark.unit

def _create_mock_post(post_id, creator_id, affinity=0.0, freshness=0.0, follows=0.0, watch_time=0.0, views_eng=0.0, tags_aff=0.0):
    return NormalizedPostRankingFeatures(
        post_id=post_id,
        creator_id=creator_id,
        views=0, likes=0, comments=0, shares=0, fast_skips=0, collab_requests=0,
        watch_time_average_percent=watch_time,
        views_engagement=views_eng, decayed_likes=0, decayed_comments=0,
        decayed_shares=0, decayed_fast_skips=0, decayed_collab_requests=0,
        follows_creator=follows, creator_affinity_score=affinity,
        tags_affinity_score=tags_aff, freshness_score=freshness,
        retrieved_source=PostRetrieveSource.RANDOM, retrieved_source_score=0.0
    )

def test_rerank_returns_empty_list_when_no_posts():
    # Arrange
    service = PostRerankerService()
    user_id = uuid4()
    
    # Act
    result = service.rerank(user_id, [])
    
    # Assert
    assert result == []

def test_rerank_sorts_by_hook_score_and_applies_diversity():
    # Arrange
    service = PostRerankerService()
    user_id = uuid4()
    
    c1, c2 = uuid4(), uuid4()
    p1 = _create_mock_post(uuid4(), c1, affinity=1.0)
    p2 = _create_mock_post(uuid4(), c1, affinity=0.9)
    p3 = _create_mock_post(uuid4(), c1, affinity=0.8)
    p4 = _create_mock_post(uuid4(), c2, affinity=0.7)
    
    # The expected behavior is:
    # hook score order: p1 (c1), p2 (c1), p3 (c1), p4 (c2)
    # diversity rule enforces max 2 consecutive posts from same creator
    # So: p1, p2, p4, p3
    posts = [p3, p1, p4, p2]
    
    # Act
    result = service.rerank(user_id, posts)
    
    # Assert
    assert result == [p1.post_id, p2.post_id, p4.post_id, p3.post_id]

def test_rerank_degrades_gracefully_when_all_remaining_are_from_same_creator():
    # Arrange
    service = PostRerankerService()
    user_id = uuid4()
    
    c1 = uuid4()
    p1 = _create_mock_post(uuid4(), c1, affinity=1.0)
    p2 = _create_mock_post(uuid4(), c1, affinity=0.9)
    p3 = _create_mock_post(uuid4(), c1, affinity=0.8)
    
    # All posts from c1. Hook score order: p1, p2, p3.
    # The rule limits 2, but there's no alternatives, so it will output p1, p2, p3.
    posts = [p1, p2, p3]
    
    # Act
    result = service.rerank(user_id, posts)
    
    # Assert
    assert result == [p1.post_id, p2.post_id, p3.post_id]
