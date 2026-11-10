import logging
from functools import partial

from pipeline.usecase.recommended_feed_use_case import RecommendedFeedUseCase
from post.command.post_commands import ExhaustPostFeedCommand
from shared.repository.processed_events_repository import ProcessedEventsRepository

logger = logging.getLogger(__name__)

PROCESSED_EVENT_NAME = "UserFeedExhaustedEvent"


class ExhaustPostFeedUsecase:
    """Answers a feed request. Order matters: check idempotency, run the pipeline
    outside any transaction, then one short transaction that marks the event
    processed and writes RecommendedPostSent to the outbox. Blocks for as long as
    the pipeline runs: call it from PipelineWorkerPool."""

    def __init__(
        self,
        recommended_feed_usecase: RecommendedFeedUseCase,
        processed_events_repository: ProcessedEventsRepository,
    ):
        self.recommended_feed_usecase = recommended_feed_usecase
        self.processed_events_repository = processed_events_repository

    def execute(self, command: ExhaustPostFeedCommand) -> None:
        if self.processed_events_repository.isAlreadyProcessed(
            event_id=command.event_id,
            correlation_id=command.correlation_id,
        ):
            logger.warning(
                "Duplicate feed request, discarding: event_id=%s correlation_id=%s user_id=%s",
                command.event_id, command.correlation_id, command.user_id,
            )
            return

        self.recommended_feed_usecase.execute(
            command.user_id,
            command.correlation_id,
            command.excluded_post_ids,
            # Two replicas can run the same redelivery at once: only one wins this row
            claim=partial(
                self.processed_events_repository.markAsProcessedIfAbsent,
                event_id=command.event_id,
                correlation_id=command.correlation_id,
                event_name=PROCESSED_EVENT_NAME,
            ),
        )
