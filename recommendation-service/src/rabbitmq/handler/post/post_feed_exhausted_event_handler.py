import logging
from functools import partial

from pipeline.worker.pipeline_worker_pool import PipelineWorkerPool
from post.command.post_commands import ExhaustPostFeedCommand
from post.usecase.exhaust_post_feed_usecase import ExhaustPostFeedUsecase
from rabbitmq.event.post.post_events import PostFeedExhaustedEvent

logger = logging.getLogger(__name__)


class PostFeedExhaustedEventHandler:
    def __init__(self, exhaust_post_feed_usecase: ExhaustPostFeedUsecase, worker_pool: PipelineWorkerPool):
        self.exhaust_post_feed_usecase = exhaust_post_feed_usecase
        self.worker_pool = worker_pool

    async def handle(self, event: PostFeedExhaustedEvent) -> str:
        command = ExhaustPostFeedCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
            user_id=event.userId,
            reason=event.reason,
            current_feed_size=event.currentFeedSize,
            excluded_post_ids=tuple(event.excludedPostIds),
        )
        logger.info(
            "Feed request received: correlation_id=%s user_id=%s reason=%s current_feed_size=%s excluded=%s",
            command.correlation_id, command.user_id, command.reason.value,
            command.current_feed_size, len(command.excluded_post_ids),
        )
        await self.worker_pool.run(partial(self.exhaust_post_feed_usecase.execute, command))
        return event.__class__.__name__
