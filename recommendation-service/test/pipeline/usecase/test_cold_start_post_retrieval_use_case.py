from unittest.mock import Mock
from uuid import uuid4

import pytest

from pipeline.enum.post_retrieve_source import PostRetrieveSource
from pipeline.repository.explorative_post_retrieval_repository import ExplorativePostRetrievalRepository
from pipeline.service.post_candidate_enricher_service import PostCandidateEnricherService
from pipeline.service.candidate_normalizer import CandidateNormalizer
from pipeline.usecase.cold_start_post_retrieval_use_case import ColdStartPostRetrievalUseCase
from pipeline.model.ranking.normalized_post_ranking_features import NormalizedPostRankingFeatures

pytestmark = pytest.mark.unit

def test_retrieve_and_prepare_cold_start_posts_returns_normalized_features():
    # Arrange
    user_id = uuid4()
    post_id_1 = uuid4()
    creator_id_1 = uuid4()
    
    repository = Mock(spec=ExplorativePostRetrievalRepository)
    repository.get_cold_start_posts.return_value = [(post_id_1, 0.0)]
    
    enricher = Mock(spec=PostCandidateEnricherService)
    enricher.enrich.return_value = ["enriched_mock"]
    
    normalizer = Mock(spec=CandidateNormalizer)
    normalized_mock = NormalizedPostRankingFeatures(
        post_id=post_id_1, creator_id=creator_id_1, views=0, likes=0, comments=0, shares=0,
        fast_skips=0, collab_requests=0, watch_time_average_percent=0, views_engagement=0,
        decayed_likes=0, decayed_comments=0, decayed_shares=0, decayed_fast_skips=0,
        decayed_collab_requests=0, follows_creator=0.0, creator_affinity_score=0.0,
        tags_affinity_score=0.0, freshness_score=1.0, retrieved_source=PostRetrieveSource.COLD_START,
        retrieved_source_score=0.0
    )
    normalizer.normalize.return_value = [normalized_mock]
    
    usecase = ColdStartPostRetrievalUseCase(repository, enricher, normalizer)

    # Act
    limit = 2
    result = usecase.retrieve_and_prepare_cold_start_posts(user_id, limit)

    # Assert
    assert len(result) == 1
    assert result[0].post_id == post_id_1
    assert result[0].retrieved_source == PostRetrieveSource.COLD_START
    
    repository.get_cold_start_posts.assert_called_once_with(user_id, limit)
    enricher.enrich.assert_called_once()
    normalizer.normalize.assert_called_once_with(["enriched_mock"])

def test_retrieve_and_prepare_cold_start_posts_returns_empty_when_no_posts():
    # Arrange
    user_id = uuid4()
    
    repository = Mock(spec=ExplorativePostRetrievalRepository)
    repository.get_cold_start_posts.return_value = []
    
    enricher = Mock(spec=PostCandidateEnricherService)
    normalizer = Mock(spec=CandidateNormalizer)
    
    usecase = ColdStartPostRetrievalUseCase(repository, enricher, normalizer)

    # Act
    result = usecase.retrieve_and_prepare_cold_start_posts(user_id, 2)

    # Assert
    assert result == []
    repository.get_cold_start_posts.assert_called_once_with(user_id, 2)
    enricher.enrich.assert_not_called()
    normalizer.normalize.assert_not_called()
