import pytest
from uuid import uuid4
from datetime import datetime, timezone, timedelta
import math

from pipeline.model.interaction.enriched_post_candidate import EnrichedPostCandidate
from pipeline.service.deterministic_candidate_normalizer import DeterministicCandidateNormalizer
from pipeline.enum.post_retrieve_source import PostRetrieveSource
from pipeline.config import constants
from shared.helpers import decay

def test_normalize_count_logic():
    normalizer = DeterministicCandidateNormalizer()
    
    # Negative -> 0
    assert normalizer._normalize_count(-50, 100) == 0.0
    
    # 0 -> 0
    assert normalizer._normalize_count(0, 100) == 0.0
    
    # Exact saturation -> 1.0
    assert math.isclose(normalizer._normalize_count(100, 100), 1.0)
    
    # Above saturation -> 1.0
    assert normalizer._normalize_count(150, 100) == 1.0
    
    # Intermediate -> (0, 1)
    val = normalizer._normalize_count(50, 100)
    assert 0.0 < val < 1.0
    
    # Invalid saturation point
    with pytest.raises(ValueError):
        normalizer._normalize_count(50, 0)
        
    with pytest.raises(ValueError):
        normalizer._normalize_count(50, -10)

def test_normalize_percentage_logic():
    normalizer = DeterministicCandidateNormalizer()
    
    assert normalizer._normalize_percentage(-10.0) == 0.0
    assert normalizer._normalize_percentage(0.0) == 0.0
    assert normalizer._normalize_percentage(50.0) == 0.5
    assert normalizer._normalize_percentage(100.0) == 1.0
    assert normalizer._normalize_percentage(150.0) == 1.0

def test_max_affinity_logic():
    normalizer = DeterministicCandidateNormalizer()
    
    assert normalizer._max_affinity([]) == 0.0
    assert normalizer._max_affinity([0.5]) == 0.5
    assert normalizer._max_affinity([0.1, 0.9, 0.5]) == 0.9

def test_normalize_empty_list():
    normalizer = DeterministicCandidateNormalizer()
    assert normalizer.normalize([]) == []

def test_normalize_orchestration(monkeypatch):
    normalizer = DeterministicCandidateNormalizer()
    
    # Fix time
    now = datetime(2025, 1, 1, 12, 0, 0, tzinfo=timezone.utc)
    class MockDatetime:
        @classmethod
        def now(cls, tz):
            return now
    monkeypatch.setattr("pipeline.service.deterministic_candidate_normalizer.datetime", MockDatetime)
    
    post_id = uuid4()
    c = EnrichedPostCandidate(
        post_id=post_id, impressions=0, views=constants.RANKING_VIEWS_SATURATION_POINT, likes=0, comments=0, shares=0,
        fast_skips=0, collab_requests=0, watch_time_average_percent=50.0,
        decayed_impressions=0.0, views_engagement=0.0, decayed_likes=0.0,
        decayed_comments=0.0, decayed_shares=0.0, decayed_fast_skips=0.0,
        decayed_collab_requests=0.0, last_decay_applied_at=now,
        follows_creator=True, creator_affinity_score=0.8, tags_affinity_score=[0.1, 0.9],
        retrieved_source=PostRetrieveSource.POPULAR, retrieved_source_score=1.5,
        created_at=now - timedelta(hours=1)
    )
    
    res = normalizer.normalize([c])
    assert len(res) == 1
    feat = res[0]
    
    assert feat.post_id == post_id
    assert math.isclose(feat.views, 1.0)
    assert feat.likes == 0.0
    assert feat.watch_time_average_percent == 0.5
    assert feat.follows_creator == 1.0
    assert feat.creator_affinity_score == 0.8
    assert feat.tags_affinity_score == 0.9
    assert feat.freshness_score == decay(c.created_at, now)
    assert feat.retrieved_source == PostRetrieveSource.POPULAR
    assert feat.retrieved_source_score == 1.5
