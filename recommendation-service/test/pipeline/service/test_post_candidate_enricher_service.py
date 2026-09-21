from datetime import datetime
from unittest.mock import Mock
from uuid import uuid4

import pytest

from pipeline.enum.post_retrieve_source import PostRetrieveSource
from pipeline.model.interaction.candidate import Candidate
from pipeline.model.post.post_features import PostFeatures
from pipeline.model.post.post_interaction_features import PostInteractionFeatures
from pipeline.model.post.post_tag_features import PostTagFeatures
from pipeline.model.user.user_creator_features import UserCreatorFeatures
from pipeline.service.post_candidate_enricher_service import PostCandidateEnricherService
from test._support.builders import decayed_stats, raw_stats


pytestmark = pytest.mark.unit


@pytest.fixture
def post_features_repo():
    return Mock()


@pytest.fixture
def post_interaction_features_repo():
    return Mock()


@pytest.fixture
def user_creator_features_repo():
    m = Mock()
    m.get_batch.return_value = []
    return m

@pytest.fixture
def post_tag_features_repo():
    m = Mock()
    m.get_post_tag_features.return_value = []
    return m

@pytest.fixture
def follow_repo():
    m = Mock()
    m.is_following_batch.return_value = set()
    return m


@pytest.fixture
def enricher(
    post_features_repo,
    post_interaction_features_repo,
    user_creator_features_repo,
    post_tag_features_repo,
    follow_repo,
):
    return PostCandidateEnricherService(
        post_features_repo,
        post_interaction_features_repo,
        user_creator_features_repo,
        post_tag_features_repo,
        follow_repo,
    )


def test_enrich_with_empty_candidates_returns_empty(enricher):
    result = enricher.enrich(uuid4(), [])
    assert result == []


def test_enrich_fetches_post_features(
    enricher, post_features_repo, post_interaction_features_repo
):
    user_id = uuid4()
    post_id = uuid4()
    candidates = [Candidate(post_id=post_id, source=PostRetrieveSource.COLLABORATIVE, score=0.85)]
    
    post_features_repo.get_post_features_batch.return_value = []
    post_interaction_features_repo.get_batch.return_value = []

    enricher.enrich(user_id, candidates)

    post_features_repo.get_post_features_batch.assert_called_once_with([post_id])


def test_enrich_fetches_post_interaction_features(
    enricher, post_features_repo, post_interaction_features_repo
):
    user_id = uuid4()
    post_id = uuid4()
    candidates = [Candidate(post_id=post_id, source=PostRetrieveSource.COLLABORATIVE, score=0.85)]
    
    post_features_repo.get_post_features_batch.return_value = []
    post_interaction_features_repo.get_batch.return_value = []

    enricher.enrich(user_id, candidates)

    post_interaction_features_repo.get_batch.assert_called_once_with([post_id])


def test_enrich_ignores_candidate_if_features_are_missing(
    enricher, post_features_repo, post_interaction_features_repo
):
    user_id = uuid4()
    post_id = uuid4()
    candidates = [Candidate(post_id=post_id, source=PostRetrieveSource.COLLABORATIVE, score=0.85)]
    
    # Missing both
    post_features_repo.get_post_features_batch.return_value = []
    post_interaction_features_repo.get_batch.return_value = []

    result = enricher.enrich(user_id, candidates)
    assert result == []


def test_enrich_fetches_creator_features_based_on_post_data(
    enricher,
    post_features_repo,
    post_interaction_features_repo,
    user_creator_features_repo,
    post_tag_features_repo,
    follow_repo
):
    user_id = uuid4()
    post_id = uuid4()
    creator_id = uuid4()
    candidates = [Candidate(post_id=post_id, source=PostRetrieveSource.COLLABORATIVE, score=0.85)]

    post_features_repo.get_post_features_batch.return_value = [
        PostFeatures(
            post_id=post_id,
            creator_id=creator_id,
            collab_id=None,
            collab_title=None,
            description="test",
            tags=["python"],
            tagged_users_ids=[],
            semantic_embedding=[],
            created_at=datetime.now(),
        )
    ]
    post_interaction_features_repo.get_batch.return_value = [
        PostInteractionFeatures(
            post_id=post_id,
            raw_interaction_stats=raw_stats(),
            decayed_interaction_stats=decayed_stats(),
            last_updated_at=datetime.now(),
            decayed_engagement_score=0.0,
        )
    ]

    enricher.enrich(user_id, candidates)

    user_creator_features_repo.get_batch.assert_called_once_with(user_id, [creator_id])


def test_enrich_fetches_tag_features_based_on_post_data(
    enricher,
    post_features_repo,
    post_interaction_features_repo,
    user_creator_features_repo,
    post_tag_features_repo,
    follow_repo
):
    user_id = uuid4()
    post_id = uuid4()
    creator_id = uuid4()
    candidates = [Candidate(post_id=post_id, source=PostRetrieveSource.COLLABORATIVE, score=0.85)]

    post_features_repo.get_post_features_batch.return_value = [
        PostFeatures(
            post_id=post_id,
            creator_id=creator_id,
            collab_id=None,
            collab_title=None,
            description="test",
            tags=["python"],
            tagged_users_ids=[],
            semantic_embedding=[],
            created_at=datetime.now(),
        )
    ]
    post_interaction_features_repo.get_batch.return_value = [
        PostInteractionFeatures(
            post_id=post_id,
            raw_interaction_stats=raw_stats(),
            decayed_interaction_stats=decayed_stats(),
            last_updated_at=datetime.now(),
            decayed_engagement_score=0.0,
        )
    ]

    enricher.enrich(user_id, candidates)

    called_tags = post_tag_features_repo.get_post_tag_features.call_args[0][1]
    assert set(called_tags) == {"python"}


def test_enrich_fetches_follow_status_based_on_post_data(
    enricher,
    post_features_repo,
    post_interaction_features_repo,
    user_creator_features_repo,
    post_tag_features_repo,
    follow_repo
):
    user_id = uuid4()
    post_id = uuid4()
    creator_id = uuid4()
    candidates = [Candidate(post_id=post_id, source=PostRetrieveSource.COLLABORATIVE, score=0.85)]

    post_features_repo.get_post_features_batch.return_value = [
        PostFeatures(
            post_id=post_id,
            creator_id=creator_id,
            collab_id=None,
            collab_title=None,
            description="test",
            tags=["python"],
            tagged_users_ids=[],
            semantic_embedding=[],
            created_at=datetime.now(),
        )
    ]
    post_interaction_features_repo.get_batch.return_value = [
        PostInteractionFeatures(
            post_id=post_id,
            raw_interaction_stats=raw_stats(),
            decayed_interaction_stats=decayed_stats(),
            last_updated_at=datetime.now(),
            decayed_engagement_score=0.0,
        )
    ]

    enricher.enrich(user_id, candidates)

    follow_repo.is_following_batch.assert_called_once_with(user_id, [creator_id])


def test_enrich_maps_raw_interaction_stats(
    enricher,
    post_features_repo,
    post_interaction_features_repo
):
    user_id = uuid4()
    post_id = uuid4()
    creator_id = uuid4()
    candidates = [Candidate(post_id=post_id, source=PostRetrieveSource.COLLABORATIVE, score=0.85)]

    post_features_repo.get_post_features_batch.return_value = [
        PostFeatures(
            post_id=post_id,
            creator_id=creator_id,
            collab_id=None,
            collab_title=None,
            description="test",
            tags=[],
            tagged_users_ids=[],
            semantic_embedding=[],
            created_at=datetime.now(),
        )
    ]
    post_interaction_features_repo.get_batch.return_value = [
        PostInteractionFeatures(
            post_id=post_id,
            raw_interaction_stats=raw_stats(impressions=100, likes=10),
            decayed_interaction_stats=decayed_stats(impressions=50.0, likes=5.0),
            last_updated_at=datetime.now(),
            decayed_engagement_score=1.5,
        )
    ]

    result = enricher.enrich(user_id, candidates)
    
    enriched = result[0]
    assert enriched.impressions == 100
    assert enriched.likes == 10


def test_enrich_maps_decayed_interaction_stats(
    enricher,
    post_features_repo,
    post_interaction_features_repo
):
    user_id = uuid4()
    post_id = uuid4()
    creator_id = uuid4()
    candidates = [Candidate(post_id=post_id, source=PostRetrieveSource.COLLABORATIVE, score=0.85)]

    post_features_repo.get_post_features_batch.return_value = [
        PostFeatures(
            post_id=post_id,
            creator_id=creator_id,
            collab_id=None,
            collab_title=None,
            description="test",
            tags=[],
            tagged_users_ids=[],
            semantic_embedding=[],
            created_at=datetime.now(),
        )
    ]
    post_interaction_features_repo.get_batch.return_value = [
        PostInteractionFeatures(
            post_id=post_id,
            raw_interaction_stats=raw_stats(impressions=100, likes=10),
            decayed_interaction_stats=decayed_stats(impressions=50.0, likes=5.0),
            last_updated_at=datetime.now(),
            decayed_engagement_score=1.5,
        )
    ]

    result = enricher.enrich(user_id, candidates)
    
    enriched = result[0]
    assert enriched.decayed_impressions == 50.0
    assert enriched.decayed_likes == 5.0


def test_enrich_maps_creator_affinity(
    enricher,
    post_features_repo,
    post_interaction_features_repo,
    user_creator_features_repo,
    post_tag_features_repo,
    follow_repo
):
    user_id = uuid4()
    post_id = uuid4()
    creator_id = uuid4()
    candidates = [Candidate(post_id=post_id, source=PostRetrieveSource.COLLABORATIVE, score=0.85)]

    post_features_repo.get_post_features_batch.return_value = [
        PostFeatures(
            post_id=post_id,
            creator_id=creator_id,
            collab_id=None,
            collab_title=None,
            description="test",
            tags=["python"],
            tagged_users_ids=[],
            semantic_embedding=[],
            created_at=datetime.now(),
        )
    ]
    post_interaction_features_repo.get_batch.return_value = [
        PostInteractionFeatures(
            post_id=post_id,
            raw_interaction_stats=raw_stats(),
            decayed_interaction_stats=decayed_stats(),
            last_updated_at=datetime.now(),
            decayed_engagement_score=1.5,
        )
    ]

    ucf = UserCreatorFeatures(user_id, creator_id, raw_stats(), decayed_stats(), datetime.now())
    ucf.affinity_score = 0.8
    user_creator_features_repo.get_batch.return_value = [ucf]

    ptf = PostTagFeatures(user_id, "python", raw_stats(), decayed_stats(), datetime.now())
    ptf.affinity_score = 0.6
    post_tag_features_repo.get_post_tag_features.return_value = [ptf]

    follow_repo.is_following_batch.return_value = {creator_id}

    result = enricher.enrich(user_id, candidates)
    
    enriched = result[0]
    assert enriched.creator_afinity_score == 0.8


def test_enrich_maps_tag_affinities(
    enricher,
    post_features_repo,
    post_interaction_features_repo,
    user_creator_features_repo,
    post_tag_features_repo,
    follow_repo
):
    user_id = uuid4()
    post_id = uuid4()
    creator_id = uuid4()
    candidates = [Candidate(post_id=post_id, source=PostRetrieveSource.COLLABORATIVE, score=0.85)]

    post_features_repo.get_post_features_batch.return_value = [
        PostFeatures(
            post_id=post_id,
            creator_id=creator_id,
            collab_id=None,
            collab_title=None,
            description="test",
            tags=["python"],
            tagged_users_ids=[],
            semantic_embedding=[],
            created_at=datetime.now(),
        )
    ]
    post_interaction_features_repo.get_batch.return_value = [
        PostInteractionFeatures(
            post_id=post_id,
            raw_interaction_stats=raw_stats(),
            decayed_interaction_stats=decayed_stats(),
            last_updated_at=datetime.now(),
            decayed_engagement_score=1.5,
        )
    ]

    ucf = UserCreatorFeatures(user_id, creator_id, raw_stats(), decayed_stats(), datetime.now())
    ucf.affinity_score = 0.8
    user_creator_features_repo.get_batch.return_value = [ucf]

    ptf = PostTagFeatures(user_id, "python", raw_stats(), decayed_stats(), datetime.now())
    ptf.affinity_score = 0.6
    post_tag_features_repo.get_post_tag_features.return_value = [ptf]

    follow_repo.is_following_batch.return_value = {creator_id}

    result = enricher.enrich(user_id, candidates)
    
    enriched = result[0]
    assert enriched.tags_affinity_score == [0.6]


def test_enrich_maps_follows_creator(
    enricher,
    post_features_repo,
    post_interaction_features_repo,
    user_creator_features_repo,
    post_tag_features_repo,
    follow_repo
):
    user_id = uuid4()
    post_id = uuid4()
    creator_id = uuid4()
    candidates = [Candidate(post_id=post_id, source=PostRetrieveSource.COLLABORATIVE, score=0.85)]

    post_features_repo.get_post_features_batch.return_value = [
        PostFeatures(
            post_id=post_id,
            creator_id=creator_id,
            collab_id=None,
            collab_title=None,
            description="test",
            tags=["python"],
            tagged_users_ids=[],
            semantic_embedding=[],
            created_at=datetime.now(),
        )
    ]
    post_interaction_features_repo.get_batch.return_value = [
        PostInteractionFeatures(
            post_id=post_id,
            raw_interaction_stats=raw_stats(),
            decayed_interaction_stats=decayed_stats(),
            last_updated_at=datetime.now(),
            decayed_engagement_score=1.5,
        )
    ]

    ucf = UserCreatorFeatures(user_id, creator_id, raw_stats(), decayed_stats(), datetime.now())
    ucf.affinity_score = 0.8
    user_creator_features_repo.get_batch.return_value = [ucf]

    ptf = PostTagFeatures(user_id, "python", raw_stats(), decayed_stats(), datetime.now())
    ptf.affinity_score = 0.6
    post_tag_features_repo.get_post_tag_features.return_value = [ptf]

    follow_repo.is_following_batch.return_value = {creator_id}

    result = enricher.enrich(user_id, candidates)
    
    enriched = result[0]
    assert enriched.follows_creator is True
