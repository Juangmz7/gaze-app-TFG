import pytest
from unittest.mock import Mock, call
from uuid import uuid4

from pipeline.usecase.recommendation_pipeline_orchestrator_use_case import RecommendationPipelineOrchestratorUseCase, TARGET_RECOMMENDED_POSTS
from pipeline.model.interaction.candidate import Candidate
from pipeline.enum.post_retrieve_source import PostRetrieveSource

@pytest.fixture
def orchestrator_and_mocks():
    mock_collab = Mock()
    mock_semantic = Mock()
    mock_explorative = Mock()
    mock_enricher = Mock()
    mock_ranker = Mock()
    mock_reranker = Mock()
    
    mock_cold_start = Mock()
    mock_cold_start.retrieve_and_prepare_cold_start_posts.return_value = []
    orchestrator = RecommendationPipelineOrchestratorUseCase(
        collaborative_retrieval_usecase=mock_collab,
        semantic_retrieval_usecase=mock_semantic,
        explorative_retrieval_usecase=mock_explorative,
        candidate_enricher_service=mock_enricher,
        post_weighted_ranker_service=mock_ranker,
        post_reranker_service=mock_reranker,
        cold_start_usecase=mock_cold_start
    )
    
    return orchestrator, mock_collab, mock_semantic, mock_explorative, mock_enricher, mock_ranker, mock_reranker

def test_recommendation_pipeline_orchestrator_calls_all_services(orchestrator_and_mocks):
    orchestrator, mock_collab, mock_semantic, mock_explorative, mock_enricher, mock_ranker, mock_reranker = orchestrator_and_mocks
    user_id = uuid4()
    
    # We need to return TARGET_RECOMMENDED_POSTS amount to avoid retries
    c1 = [Candidate(uuid4(), PostRetrieveSource.COLLABORATIVE, 1.0) for _ in range(10)]
    c2 = [Candidate(uuid4(), PostRetrieveSource.SEMANTIC, 1.0) for _ in range(10)]
    c3 = [Candidate(uuid4(), PostRetrieveSource.POPULAR, 1.0) for _ in range(15)]
    
    mock_collab.retrieve_posts.return_value = c1
    mock_semantic.retrieve_posts.return_value = c2
    mock_explorative.retrieve_posts.return_value = c3
    
    mock_enricher.enrich.return_value = ["e1", "e2", "e3"]
    mock_ranker.get_top_k_posts.return_value = ["top1", "top2"]
    mock_reranker.rerank.return_value = ["reranked1", "reranked2"]
    
    result = orchestrator.recommend(user_id)
    
    mock_collab.retrieve_posts.assert_called_once_with(user_id, 1.0)
    mock_semantic.retrieve_posts.assert_called_once_with(user_id, 1.0)
    mock_explorative.retrieve_posts.assert_called_once_with(user_id, 1.0)
    
    # Check deduplicated candidates passed to enricher
    # Since they are unique, it should be c1 + c2 + c3
    called_candidates = mock_enricher.enrich.call_args[0][1]
    assert len(called_candidates) == 35
    
    mock_ranker.get_top_k_posts.assert_called_once_with(["e1", "e2", "e3"], TARGET_RECOMMENDED_POSTS)
    mock_reranker.rerank.assert_called_once_with(user_id, ["top1", "top2"])
    
    assert result == ["reranked1", "reranked2"]

def test_recommendation_pipeline_retries_and_deduplicates(orchestrator_and_mocks):
    orchestrator, mock_collab, mock_semantic, mock_explorative, mock_enricher, mock_ranker, mock_reranker = orchestrator_and_mocks
    user_id = uuid4()
    
    dup_id = uuid4()
    # First pass: returns a duplicate and only 2 distinct posts total
    c1_1 = [Candidate(dup_id, PostRetrieveSource.COLLABORATIVE, 1.0)]
    c2_1 = [Candidate(dup_id, PostRetrieveSource.SEMANTIC, 1.0)]
    c3_1 = [Candidate(uuid4(), PostRetrieveSource.POPULAR, 1.0)]
    
    # Second pass (retry): returns 30 unique posts
    c1_2 = [Candidate(uuid4(), PostRetrieveSource.COLLABORATIVE, 1.0) for _ in range(30)]
    c2_2 = []
    c3_2 = []
    
    mock_collab.retrieve_posts.side_effect = [c1_1, c1_2]
    mock_semantic.retrieve_posts.side_effect = [c2_1, c2_2]
    mock_explorative.retrieve_posts.side_effect = [c3_1, c3_2]
    
    mock_enricher.enrich.return_value = ["e1"]
    mock_ranker.get_top_k_posts.return_value = ["top1"]
    mock_reranker.rerank.return_value = ["top1"]
    
    result = orchestrator.recommend(user_id)
    
    assert mock_collab.retrieve_posts.call_count == 2
    mock_collab.retrieve_posts.assert_has_calls([call(user_id, 1.0), call(user_id, 1.5)])
    
    called_candidates = mock_enricher.enrich.call_args[0][1]
    assert len(called_candidates) == 30 # From second pass
    
def test_recommendation_pipeline_empty_retrievals(orchestrator_and_mocks):
    orchestrator, mock_collab, mock_semantic, mock_explorative, mock_enricher, mock_ranker, mock_reranker = orchestrator_and_mocks
    user_id = uuid4()
    
    mock_collab.retrieve_posts.return_value = []
    mock_semantic.retrieve_posts.return_value = []
    mock_explorative.retrieve_posts.return_value = []
    
    result = orchestrator.recommend(user_id)
    
    assert result == []
    # It should have retried 3 times
    assert mock_collab.retrieve_posts.call_count == 3
    mock_enricher.enrich.assert_not_called()
    mock_ranker.get_top_k_posts.assert_not_called()
    mock_reranker.rerank.assert_not_called()

def test_recommendation_pipeline_empty_enrichment(orchestrator_and_mocks):
    orchestrator, mock_collab, mock_semantic, mock_explorative, mock_enricher, mock_ranker, mock_reranker = orchestrator_and_mocks
    user_id = uuid4()
    
    mock_collab.retrieve_posts.return_value = [Candidate(uuid4(), PostRetrieveSource.COLLABORATIVE, 1.0)]
    mock_semantic.retrieve_posts.return_value = []
    mock_explorative.retrieve_posts.return_value = []
    
    mock_enricher.enrich.return_value = []
    
    result = orchestrator.recommend(user_id)
    
    assert result == []
    mock_ranker.get_top_k_posts.assert_not_called()
    mock_reranker.rerank.assert_not_called()
    
def test_recommendation_pipeline_empty_ranking(orchestrator_and_mocks):
    orchestrator, mock_collab, mock_semantic, mock_explorative, mock_enricher, mock_ranker, mock_reranker = orchestrator_and_mocks
    user_id = uuid4()
    
    mock_collab.retrieve_posts.return_value = [Candidate(uuid4(), PostRetrieveSource.COLLABORATIVE, 1.0)]
    mock_semantic.retrieve_posts.return_value = []
    mock_explorative.retrieve_posts.return_value = []
    
    mock_enricher.enrich.return_value = ["e1"]
    mock_ranker.get_top_k_posts.return_value = []
    
    result = orchestrator.recommend(user_id)
    
    assert result == []
    mock_reranker.rerank.assert_not_called()
