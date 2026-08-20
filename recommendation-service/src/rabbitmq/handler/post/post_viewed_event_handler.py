from post.command.post_commands import RegisterPostViewCommand
from post.usecase.register_post_view_usecase import RegisterPostViewUsecase
from rabbitmq.event.post.post_events import PostViewedEvent


class PostViewedEventHandler:
    @staticmethod
    async def handle(event: PostViewedEvent) -> str:
        command = RegisterPostViewCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
            view_id=event.viewId,
            post_id=event.postId,
            user_id=event.userId,
            source=event.source,
            feed_position=event.feedPosition,
            duration_ms=event.durationMs,
            time_watched_ms=event.timeWatchedMs,
            completion_percent=event.completionPercent,
            exit_reason=event.exitReason,
            server_timestamp=event.serverTimestamp,
            replay_count=event.replayCount,
        )
        RegisterPostViewUsecase.execute(command)
        return event.__class__.__name__
