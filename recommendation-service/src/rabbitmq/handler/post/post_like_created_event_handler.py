import logging

from pipeline.exceptions.exceptions import DontRequeuePipelineException
from post.command.post_commands import CreatePostLikeCommand
from post.usecase.create_post_like_usecase import CreatePostLikeUsecase
from rabbitmq.event.post.post_events import PostLikeCreatedEvent
from rabbitmq.exception.exceptions import RejectAndDontRequeueError

logger = logging.getLogger(__name__)

class PostLikeCreatedEventHandler:
    def __init__(self, create_post_like_usecase: CreatePostLikeUsecase):
        self.create_post_like_usecase = create_post_like_usecase

    async def handle(self, event: PostLikeCreatedEvent) -> str:
        command = CreatePostLikeCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
            post_id=event.postId,
            user_id=event.userId,
            source=event.source,
            feed_position=event.feedPosition,
        )
        try:
            self.create_post_like_usecase.execute(command)

        except DontRequeuePipelineException as exc:
            logger.warning("Pipeline exception: %s", exc)
            raise RejectAndDontRequeueError(
                f"Pipeline exception: {exc}"
            ) from exc
        except Exception as exc:
            logger.exception("Unsuported exception: %s", exc)
            raise
        
        return event.__class__.__name__
