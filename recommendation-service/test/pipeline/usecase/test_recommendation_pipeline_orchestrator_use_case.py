import pytest
from unittest.mock import Mock
from uuid import uuid4

from pipeline.usecase.recommendation_pipeline_orchestrator_use_case import RecommendationPipelineOrchestratorUseCase

@pytest.fixture
def orchestrator_and_mocks():
    mock_collab = Mock()
    mock_semantic = Mock()
    mock_explorative = Mock()
    mock_enricher = Mock()
    mock_ranker = Mock()
    mock_reranker = Mock()
    
    orchestrator = RecommendationPipelineOrchestratorUseCase(
        collaborative_retrieval_usecase=mock_collab,
        semantic_retrieval_usecase=mock_semantic,
        explorative_retrieval_usecase=mock_explorative,
        candidate_enricher_service=mock_enricher,
        post_weighted_ranker_service=mock_ranker,
        post_reranker_service=mock_reranker
    )
    
    return orchestrator, mock_collab, mock_semantic, mock_explorative, mock_enricher, mock_ranker, mock_reranker

def test_recommendation_pipeline_orchestrator_calls_all_services(orchestrator_and_mocks):
    orchestrator, mock_collab, mock_semantic, mock_explorative, mock_enricher, mock_ranker, mock_reranker = orchestrator_and_mocks
    user_id = uuid4()
    
    mock_collab.retrieve_posts.return_value = ["c1"]
    mock_semantic.retrieve_posts.return_value = ["c2"]
    mock_explorative.retrieve_posts.return_value = ["c3"]
    
    mock_enricher.enrich.return_value = ["e1", "e2", "e3"]
    mock_ranker.get_top_k_posts.return_value = ["top1", "top2"]
    mock_reranker.rerank.return_value = ["reranked1", "reranked2"]
    
    result = orchestrator.recommend(user_id)
    
    mock_collab.retrieve_posts.assert_called_once_with(user_id)
    mock_semantic.retrieve_posts.assert_called_once_with(user_id)
    mock_explorative.retrieve_posts.assert_called_once_with(user_id)
    
    mock_enricher.enrich.assert_called_once_with(user_id, ["c1", "c2", "c3"])
    mock_ranker.get_top_k_posts.assert_called_once_with(["e1", "e2", "e3"], 30)
    mock_reranker.rerank.assert_called_once_with(user_id, ["top1", "top2"])
    
    assert result == ["reranked1", "reranked2"]

def test_recommendation_pipeline_empty_retrievals(orchestrator_and_mocks):
    orchestrator, mock_collab, mock_semantic, mock_explorative, mock_enricher, mock_ranker, mock_reranker = orchestrator_and_mocks
    user_id = uuid4()
    
    mock_collab.retrieve_posts.return_value = []
    mock_semantic.retrieve_posts.return_value = []
    mock_explorative.retrieve_posts.return_value = []
    
    result = orchestrator.recommend(user_id)
    
    assert result == []
    mock_enricher.enrich.assert_not_called()
    mock_ranker.get_top_k_posts.assert_not_called()
    mock_reranker.rerank.assert_not_called()

def test_recommendation_pipeline_empty_enrichment(orchestrator_and_mocks):
    orchestrator, mock_collab, mock_semantic, mock_explorative, mock_enricher, mock_ranker, mock_reranker = orchestrator_and_mocks
    user_id = uuid4()
    
    mock_collab.retrieve_posts.return_value = ["c1"]
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
    
    mock_collab.retrieve_posts.return_value = ["c1"]
    mock_semantic.retrieve_posts.return_value = []
    mock_explorative.retrieve_posts.return_value = []
    
    mock_enricher.enrich.return_value = ["e1"]
    mock_ranker.get_top_k_posts.return_value = []
    
    result = orchestrator.recommend(user_id)
    
    assert result == []
    mock_reranker.rerank.assert_not_called()
