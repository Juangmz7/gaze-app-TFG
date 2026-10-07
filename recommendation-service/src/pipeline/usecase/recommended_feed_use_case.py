import logging
from uuid import UUID

from pipeline.usecase.recommendation_pipeline_orchestrator_use_case import (
    RecommendationPipelineOrchestratorUseCase,
)
from rabbitmq.config.constants import FEED_EXCHANGE, FEED_RECOMMENDED_SENT_RK
from rabbitmq.event.feed.feed_events import RecommendedPostSentEvent
from shared.config.database import SQLAlchemyTransactionManager
from shared.outbox.outbox_publisher import OutboxPublisher

logger = logging.getLogger(__name__)


class RecommendedFeedUseCase:
    """Runs the recommendation pipeline for a user and publishes the result as
    RecommendedPostSent through the outbox. Not wired to a trigger yet."""

    def __init__(
        self,
        orchestrator: RecommendationPipelineOrchestratorUseCase,
        outbox_publisher: OutboxPublisher,
        transaction_manager: SQLAlchemyTransactionManager,
    ):
        self.orchestrator = orchestrator
        self.outbox_publisher = outbox_publisher
        self.transaction_manager = transaction_manager

    def execute(self, user_id: UUID, correlation_id: UUID) -> None:
        # 1. Heavy ML + reads, outside any write transaction
        recommended_posts = self.orchestrator.recommend(user_id)

        # 2. One short transaction for the outbox insert (and any final state
        #    write the pipeline adds later), so they commit atomically
        with self.transaction_manager.transaction() as session:
            self.outbox_publisher.add(
                session,
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
