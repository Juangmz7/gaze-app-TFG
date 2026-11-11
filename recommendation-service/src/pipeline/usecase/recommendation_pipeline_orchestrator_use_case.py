import logging
import time
from collections.abc import Collection, Iterator
from contextlib import contextmanager
from uuid import UUID

from pipeline.usecase.cold_start_post_retrieval_use_case import ColdStartPostRetrievalUseCase
import math
from pipeline.model.interaction.candidate import Candidate
from pipeline.model.ranking.normalized_post_ranking_features import NormalizedPostRankingFeatures
from pipeline.usecase.collaborative_post_retrieval_use_case import CollaborativePostRetrievalUseCase
from pipeline.usecase.semantic_post_retrieval_use_case import SemanticPostRetrievalUseCase
from pipeline.usecase.explorative_post_retrieval_use_case import ExplorativePostRetrievalUsecase
from pipeline.service.post_candidate_enricher_service import PostCandidateEnricherService
from pipeline.service.post_weighted_ranker_service import PostWeightedRankerService
from pipeline.service.post_reranker_service import PostRerankerService

TARGET_RECOMMENDED_POSTS = 28
MAX_RETRIEVAL_RETRIES = 3
RETRIEVAL_MULTIPLIER_STEP = 0.5
COLD_START_MULTIPLIER = 0.05


logger = logging.getLogger(__name__)


## Milliseconds per pipeline stage, for the one summary line of a run.
class _StageTimer:
    def __init__(self) -> None:
        self._durations_ms: dict[str, float] = {}

    @contextmanager
    def measure(self, stage: str) -> Iterator[None]:
        started_at = time.perf_counter()
        try:
            yield
        finally:
            self._durations_ms[stage] = (time.perf_counter() - started_at) * 1000

    def summary(self) -> str:
        return " ".join(f"{stage}_ms={duration:.0f}" for stage, duration in self._durations_ms.items())


class RecommendationPipelineOrchestratorUseCase:
    def __init__(
        self,
        collaborative_retrieval_usecase: CollaborativePostRetrievalUseCase,
        semantic_retrieval_usecase: SemanticPostRetrievalUseCase,
        explorative_retrieval_usecase: ExplorativePostRetrievalUsecase,
        candidate_enricher_service: PostCandidateEnricherService,
        post_weighted_ranker_service: PostWeightedRankerService,
        post_reranker_service: PostRerankerService,
        cold_start_usecase: ColdStartPostRetrievalUseCase,
    ):
        self.collaborative_retrieval_usecase = collaborative_retrieval_usecase
        self.semantic_retrieval_usecase = semantic_retrieval_usecase
        self.explorative_retrieval_usecase = explorative_retrieval_usecase
        self.candidate_enricher_service = candidate_enricher_service
        self.post_weighted_ranker_service = post_weighted_ranker_service
        self.post_reranker_service = post_reranker_service
        self.cold_start_usecase = cold_start_usecase

    def recommend(self, user_id: UUID, excluded_post_ids: Collection[UUID] = ()) -> list[UUID]:
        """Post ids for the user, best first: up to TARGET_RECOMMENDED_POSTS ranked
        posts plus the cold start ones. Never contains excluded_post_ids. Having no
        candidates or no features is a normal result, not an error."""
        excluded = set(excluded_post_ids)
        logger.info("Starting recommendation pipeline: user_id=%s excluded=%s", user_id, len(excluded))
        stages = _StageTimer()

        with stages.measure("retrieval"):
            candidates, attempts = self._retrieve_and_deduplicate(user_id, excluded)
        with stages.measure("enrich_rank"):
            top_k_normalized_posts = self._enrich_and_rank(user_id, candidates)

        cold_start_limit = max(1, math.ceil(TARGET_RECOMMENDED_POSTS * COLD_START_MULTIPLIER))
        logger.debug("Fetching %s cold start posts: user_id=%s", cold_start_limit, user_id)

        # Cold start only adds posts neither the ranker nor the caller already has
        ranked_post_ids = {p.post_id for p in top_k_normalized_posts}
        with stages.measure("cold_start"):
            cold_start_normalized_posts = self.cold_start_usecase.retrieve_and_prepare_cold_start_posts(
                user_id, cold_start_limit, ranked_post_ids | excluded
            )

        # Combine the lists
        combined_posts = top_k_normalized_posts + cold_start_normalized_posts

        reranked_post_ids: list[UUID] = []
        if combined_posts:
            logger.debug("Reranking %s combined top posts: user_id=%s", len(combined_posts), user_id)
            with stages.measure("rerank"):
                reranked_post_ids = self.post_reranker_service.rerank(user_id, combined_posts)
        else:
            # Normal with few posts; RecommendedFeedUseCase warns once about the empty result
            logger.debug("No eligible posts: user_id=%s", user_id)

        # One line per run, with where the time went: the total is in RecommendedFeedUseCase's
        logger.info(
            "Recommendation pipeline stages: user_id=%s %s attempts=%s candidates=%s ranked=%s cold_start=%s posts=%s",
            user_id, stages.summary(), attempts, len(candidates), len(top_k_normalized_posts),
            len(cold_start_normalized_posts), len(reranked_post_ids),
        )
        return reranked_post_ids

    def _enrich_and_rank(self, user_id: UUID, candidates: list[Candidate]) -> list[NormalizedPostRankingFeatures]:
        if not candidates:
            # Normal for a user with nothing left to discover: cold start still runs
            logger.debug("No candidates retrieved: user_id=%s", user_id)
            return []

        logger.debug("Enriching %s candidates: user_id=%s", len(candidates), user_id)
        enriched_candidates = self.candidate_enricher_service.enrich(user_id, candidates)

        if not enriched_candidates:
            # There were candidates, but none had features: a projection is missing
            logger.warning("Enricher dropped every candidate: user_id=%s candidates=%s", user_id, len(candidates))
            return []

        logger.debug("Ranking %s enriched candidates: user_id=%s", len(enriched_candidates), user_id)
        top_k_normalized_posts = self.post_weighted_ranker_service.get_top_k_posts(
            enriched_candidates, TARGET_RECOMMENDED_POSTS
        )

        if not top_k_normalized_posts:
            logger.warning("Ranker returned no posts: user_id=%s enriched=%s", user_id, len(enriched_candidates))

        return top_k_normalized_posts

    def _retrieve_and_deduplicate(self, user_id: UUID, excluded_post_ids: set[UUID]) -> tuple[list[Candidate], int]:
        """The eligible candidates and how many retrieval attempts it took."""
        # Personalised retrieval compares against the user's embedding: without
        # signal (or without a features row) it has nothing to compare with
        has_signal = self.collaborative_retrieval_usecase.has_semantic_signal(user_id)
        multiplier = 1.0

        for attempt in range(1, MAX_RETRIEVAL_RETRIES + 1):
            candidates = self._retrieve(user_id, multiplier, has_signal)

            # Exclusions are removed before counting, so the target counts only eligible posts
            seen_ids = set(excluded_post_ids)
            deduplicated = []
            for c in candidates:
                if c.post_id not in seen_ids:
                    seen_ids.add(c.post_id)
                    deduplicated.append(c)

            logger.debug(
                "Retrieval attempt %s/%s: user_id=%s multiplier=%s raw=%s eligible=%s target=%s",
                attempt, MAX_RETRIEVAL_RETRIES, user_id, multiplier, len(candidates), len(deduplicated),
                TARGET_RECOMMENDED_POSTS,
            )
            if len(deduplicated) >= TARGET_RECOMMENDED_POSTS:
                return deduplicated, attempt

            multiplier += RETRIEVAL_MULTIPLIER_STEP

        # Few posts to choose from, not a failure: the summary line shows attempts and candidates
        logger.info(
            "Retrieval stopped below the target after %s attempts: user_id=%s eligible=%s target=%s",
            MAX_RETRIEVAL_RETRIES, user_id, len(deduplicated), TARGET_RECOMMENDED_POSTS,
        )
        return deduplicated, MAX_RETRIEVAL_RETRIES

    def _retrieve(self, user_id: UUID, multiplier: float, has_signal: bool) -> list[Candidate]:
        if not has_signal:
            return self.explorative_retrieval_usecase.retrieve_posts_for_new_user(user_id, multiplier)

        candidates = []
        candidates.extend(self.collaborative_retrieval_usecase.retrieve_posts(user_id, multiplier))
        candidates.extend(self.semantic_retrieval_usecase.retrieve_posts(user_id, multiplier))
        candidates.extend(self.explorative_retrieval_usecase.retrieve_posts(user_id, multiplier))
        return candidates
