import logging
import time
from collections.abc import Callable, Collection
from uuid import UUID

from pipeline.usecase.recommendation_pipeline_orchestrator_use_case import (
    RecommendationPipelineOrchestratorUseCase,
)
from rabbitmq.config.constants import FEED_EXCHANGE, FEED_RECOMMENDED_SENT_RK
from rabbitmq.event.feed.feed_events import RecommendedPostSentEvent
from shared.transaction.transaction_manager import TransactionManager
from shared.outbox.outbox_publisher import OutboxPublisher

logger = logging.getLogger(__name__)


class RecommendedFeedUseCase:
    """The one way into the recommendation pipeline for both transports. These
    methods block for as long as the pipeline runs: call them from
    PipelineWorkerPool, never on the event loop."""

    def __init__(
        self,
        orchestrator: RecommendationPipelineOrchestratorUseCase,
        outbox_publisher: OutboxPublisher,
        transaction_manager: TransactionManager,
    ):
        self.orchestrator = orchestrator
        self.outbox_publisher = outbox_publisher
        self.transaction_manager = transaction_manager

    def recommend(
        self,
        user_id: UUID,
        correlation_id: UUID | str,
        excluded_post_ids: Collection[UUID] = (),
    ) -> list[UUID]:
        """Runs the pipeline and returns the ordered ids. Writes nothing. Here the
        correlation id only feeds logs, so gRPC can pass any usable value. execute()
        needs a UUID, because it is the lease token of post-query-service."""
        started_at = time.perf_counter()
        recommended_posts = self.orchestrator.recommend(user_id, excluded_post_ids)
        duration_ms = (time.perf_counter() - started_at) * 1000

        logger.info(
            "Recommendation pipeline finished: correlation_id=%s user_id=%s duration_ms=%.0f posts=%s excluded=%s",
            correlation_id, user_id, duration_ms, len(recommended_posts), len(excluded_post_ids),
        )
        if not recommended_posts:
            logger.warning(
                "Recommendation pipeline returned no posts: correlation_id=%s user_id=%s",
                correlation_id, user_id,
            )
        return recommended_posts

    def execute(
        self,
        user_id: UUID,
        correlation_id: UUID,
        excluded_post_ids: Collection[UUID] = (),
        claim: Callable[[], bool] | None = None,
    ) -> bool:
        """Runs the pipeline and publishes the result as RecommendedPostSent through
        the outbox, also when it is empty: post-query-service releases its lease on
        it. claim runs in the same transaction as the outbox insert; when it returns
        False nothing is published. Returns whether the event was published."""
        # 1. Heavy ML + reads, outside any write transaction
        recommended_posts = self.recommend(user_id, correlation_id, excluded_post_ids)

        # 2. One short transaction for the outbox insert and the claim, so they commit atomically
        with self.transaction_manager.transaction():
            if claim is not None and not claim():
                logger.warning(
                    "RecommendedPostSent not queued, claim lost: user_id=%s correlation_id=%s",
                    user_id, correlation_id,
                )
                return False

            self.outbox_publisher.add(
                RecommendedPostSentEvent(
                    correlationId=correlation_id,
                    userId=user_id,
                    posts=recommended_posts,
                ),
                event_type="RecommendedPostSent",
                exchange=FEED_EXCHANGE,
                routing_key=FEED_RECOMMENDED_SENT_RK,
            )

        logger.info(
            "RecommendedPostSent queued in outbox: user_id=%s posts=%s correlation_id=%s",
            user_id, len(recommended_posts), correlation_id,
        )
        return True
