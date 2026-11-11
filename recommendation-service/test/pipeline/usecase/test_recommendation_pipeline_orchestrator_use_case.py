import math

import pytest
from types import SimpleNamespace
from unittest.mock import Mock, call
from uuid import uuid4

from pipeline.usecase.recommendation_pipeline_orchestrator_use_case import (
    COLD_START_MULTIPLIER,
    TARGET_RECOMMENDED_POSTS,
    RecommendationPipelineOrchestratorUseCase,
)
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


def ranked_post():
    return SimpleNamespace(post_id=uuid4())

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
    top1, top2 = ranked_post(), ranked_post()
    mock_ranker.get_top_k_posts.return_value = [top1, top2]
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
    mock_reranker.rerank.assert_called_once_with(user_id, [top1, top2])
    
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
    mock_ranker.get_top_k_posts.return_value = [ranked_post()]
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


def test_recommendation_pipeline_excludes_ranked_posts_from_cold_start():
    mock_collab, mock_semantic, mock_explorative = Mock(), Mock(), Mock()
    mock_enricher, mock_ranker, mock_reranker, mock_cold_start = Mock(), Mock(), Mock(), Mock()
    orchestrator = RecommendationPipelineOrchestratorUseCase(
        collaborative_retrieval_usecase=mock_collab,
        semantic_retrieval_usecase=mock_semantic,
        explorative_retrieval_usecase=mock_explorative,
        candidate_enricher_service=mock_enricher,
        post_weighted_ranker_service=mock_ranker,
        post_reranker_service=mock_reranker,
        cold_start_usecase=mock_cold_start,
    )
    user_id = uuid4()

    mock_collab.retrieve_posts.return_value = [
        Candidate(uuid4(), PostRetrieveSource.COLLABORATIVE, 1.0) for _ in range(TARGET_RECOMMENDED_POSTS)
    ]
    mock_semantic.retrieve_posts.return_value = []
    mock_explorative.retrieve_posts.return_value = []
    mock_enricher.enrich.return_value = ["e1"]
    top1, top2 = ranked_post(), ranked_post()
    mock_ranker.get_top_k_posts.return_value = [top1, top2]
    cold = ranked_post()
    mock_cold_start.retrieve_and_prepare_cold_start_posts.return_value = [cold]
    mock_reranker.rerank.return_value = [top1.post_id, top2.post_id, cold.post_id]

    orchestrator.recommend(user_id)

    mock_cold_start.retrieve_and_prepare_cold_start_posts.assert_called_once_with(
        user_id, max(1, math.ceil(TARGET_RECOMMENDED_POSTS * COLD_START_MULTIPLIER)), {top1.post_id, top2.post_id}
    )
    mock_reranker.rerank.assert_called_once_with(user_id, [top1, top2, cold])


def build_orchestrator():
    mocks = SimpleNamespace(
        collab=Mock(), semantic=Mock(), explorative=Mock(), enricher=Mock(), ranker=Mock(), reranker=Mock(), cold_start=Mock(),
    )
    mocks.collab.has_semantic_signal.return_value = True
    mocks.collab.retrieve_posts.return_value = []
    mocks.semantic.retrieve_posts.return_value = []
    mocks.explorative.retrieve_posts.return_value = []
    mocks.explorative.retrieve_posts_for_new_user.return_value = []
    mocks.enricher.enrich.side_effect = lambda user_id, candidates: candidates
    mocks.ranker.get_top_k_posts.side_effect = lambda candidates, k: [SimpleNamespace(post_id=c.post_id) for c in candidates[:k]]
    mocks.reranker.rerank.side_effect = lambda user_id, posts: [p.post_id for p in posts]
    mocks.cold_start.retrieve_and_prepare_cold_start_posts.return_value = []
    orchestrator = RecommendationPipelineOrchestratorUseCase(
        collaborative_retrieval_usecase=mocks.collab,
        semantic_retrieval_usecase=mocks.semantic,
        explorative_retrieval_usecase=mocks.explorative,
        candidate_enricher_service=mocks.enricher,
        post_weighted_ranker_service=mocks.ranker,
        post_reranker_service=mocks.reranker,
        cold_start_usecase=mocks.cold_start,
    )
    return orchestrator, mocks


def popular(post_ids):
    return [Candidate(post_id, PostRetrieveSource.POPULAR, 1.0) for post_id in post_ids]


def test_should_remove_excluded_ids_before_counting_the_target():
    # Arrange: the first pass reaches the target only by counting the excluded posts
    orchestrator, mocks = build_orchestrator()
    user_id = uuid4()
    excluded = [uuid4() for _ in range(5)]
    first_pass = excluded + [uuid4() for _ in range(TARGET_RECOMMENDED_POSTS - 5)]
    second_pass = excluded + [uuid4() for _ in range(TARGET_RECOMMENDED_POSTS)]
    mocks.explorative.retrieve_posts.side_effect = [popular(first_pass), popular(second_pass)]

    # Act
    result = orchestrator.recommend(user_id, excluded)

    # Assert: it had to retrieve again, and still returns the whole target
    assert mocks.explorative.retrieve_posts.call_count == 2
    assert len(result) == TARGET_RECOMMENDED_POSTS
    assert not set(result) & set(excluded)


def test_should_pass_the_excluded_ids_to_cold_start_merged_with_the_ranked_ids():
    orchestrator, mocks = build_orchestrator()
    user_id = uuid4()
    excluded, ranked = uuid4(), uuid4()
    mocks.explorative.retrieve_posts.return_value = popular([excluded, ranked])

    orchestrator.recommend(user_id, [excluded])

    mocks.cold_start.retrieve_and_prepare_cold_start_posts.assert_called_once_with(
        user_id, max(1, math.ceil(TARGET_RECOMMENDED_POSTS * COLD_START_MULTIPLIER)), {excluded, ranked}
    )


def test_should_use_the_new_user_retrieval_for_a_user_without_signal():
    # Arrange
    orchestrator, mocks = build_orchestrator()
    user_id = uuid4()
    post_ids = [uuid4() for _ in range(TARGET_RECOMMENDED_POSTS)]
    mocks.collab.has_semantic_signal.return_value = False
    mocks.explorative.retrieve_posts_for_new_user.return_value = popular(post_ids)

    # Act
    result = orchestrator.recommend(user_id)

    # Assert: personalised retrieval needs an embedding this user does not have
    assert result == post_ids
    mocks.explorative.retrieve_posts_for_new_user.assert_called_once_with(user_id, 1.0)
    mocks.collab.retrieve_posts.assert_not_called()
    mocks.semantic.retrieve_posts.assert_not_called()
    mocks.explorative.retrieve_posts.assert_not_called()


def test_should_still_attempt_cold_start_with_no_candidates():
    # Arrange
    orchestrator, mocks = build_orchestrator()
    user_id = uuid4()
    cold = ranked_post()
    mocks.collab.has_semantic_signal.return_value = False
    mocks.cold_start.retrieve_and_prepare_cold_start_posts.return_value = [cold]

    # Act
    result = orchestrator.recommend(user_id)

    # Assert
    assert result == [cold.post_id]
    mocks.enricher.enrich.assert_not_called()
    mocks.cold_start.retrieve_and_prepare_cold_start_posts.assert_called_once()


@pytest.mark.parametrize("empty_stage", ["enricher", "ranker"])
def test_should_still_attempt_cold_start_when_a_ranking_stage_returns_nothing(empty_stage):
    # Arrange
    orchestrator, mocks = build_orchestrator()
    cold = ranked_post()
    mocks.explorative.retrieve_posts.return_value = popular([uuid4()])
    mocks.cold_start.retrieve_and_prepare_cold_start_posts.return_value = [cold]
    if empty_stage == "enricher":
        mocks.enricher.enrich.side_effect = None
        mocks.enricher.enrich.return_value = []
    else:
        mocks.ranker.get_top_k_posts.side_effect = None
        mocks.ranker.get_top_k_posts.return_value = []

    # Act
    result = orchestrator.recommend(uuid4())

    # Assert
    assert result == [cold.post_id]


def test_should_return_nothing_without_candidates_and_cold_start_posts():
    orchestrator, mocks = build_orchestrator()

    assert orchestrator.recommend(uuid4()) == []
    mocks.reranker.rerank.assert_not_called()


def test_should_log_one_summary_line_per_run_with_the_time_of_each_stage(orchestrator_and_mocks, caplog):
    # Arrange
    orchestrator, mock_collab, mock_semantic, mock_explorative, mock_enricher, mock_ranker, mock_reranker = orchestrator_and_mocks
    user_id = uuid4()
    mock_collab.retrieve_posts.return_value = [Candidate(uuid4(), PostRetrieveSource.COLLABORATIVE, 1.0) for _ in range(TARGET_RECOMMENDED_POSTS)]
    mock_semantic.retrieve_posts.return_value = []
    mock_explorative.retrieve_posts.return_value = []
    mock_enricher.enrich.return_value = ["e1"]
    mock_ranker.get_top_k_posts.return_value = [ranked_post()]
    mock_reranker.rerank.return_value = ["reranked1"]

    # Act
    with caplog.at_level("INFO", logger="pipeline.usecase.recommendation_pipeline_orchestrator_use_case"):
        orchestrator.recommend(user_id)

    # Assert
    summaries = [r.getMessage() for r in caplog.records if r.getMessage().startswith("Recommendation pipeline stages")]
    assert len(summaries) == 1
    for field in (f"user_id={user_id}", "retrieval_ms=", "enrich_rank_ms=", "cold_start_ms=", "rerank_ms=",
                  "attempts=1", f"candidates={TARGET_RECOMMENDED_POSTS}", "ranked=1", "posts=1"):
        assert field in summaries[0], field


def test_should_not_warn_when_there_are_just_few_posts_to_choose_from(orchestrator_and_mocks, caplog):
    # Arrange: fewer candidates than the target on every attempt, which is normal with few posts
    orchestrator, mock_collab, mock_semantic, mock_explorative, mock_enricher, mock_ranker, mock_reranker = orchestrator_and_mocks
    mock_collab.retrieve_posts.return_value = [Candidate(uuid4(), PostRetrieveSource.COLLABORATIVE, 1.0)]
    mock_semantic.retrieve_posts.return_value = []
    mock_explorative.retrieve_posts.return_value = []
    mock_enricher.enrich.return_value = ["e1"]
    mock_ranker.get_top_k_posts.return_value = [ranked_post()]
    mock_reranker.rerank.return_value = ["reranked1"]

    # Act
    with caplog.at_level("WARNING"):
        orchestrator.recommend(uuid4())

    # Assert
    assert [r.getMessage() for r in caplog.records if r.levelname == "WARNING"] == []
