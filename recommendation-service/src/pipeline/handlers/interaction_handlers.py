from pipeline.commands.interaction_commands import (
    CreatePostCollabRequestCommand,
    CreatePostCommentCommand,
    CreatePostCommentLikeCommand,
    CreatePostLikeCommand,
    CreatePostShareCommand,
    CreatePostViewCommand,
    DeletePostCollabRequestCommand,
    DeletePostCommentCommand,
    DeletePostCommentLikeCommand,
    DeletePostLikeCommand,
    DeletePostShareCommand,
)
from pipeline.usecase.interaction_usecases import (
    CreatePostCollabRequestUsecase,
    CreatePostCommentLikeUsecase,
    CreatePostCommentUsecase,
    CreatePostLikeUsecase,
    CreatePostShareUsecase,
    CreatePostViewUsecase,
    DeletePostCollabRequestUsecase,
    DeletePostCommentLikeUsecase,
    DeletePostCommentUsecase,
    DeletePostLikeUsecase,
    DeletePostShareUsecase,
)
from rabbitmq.event.post.post_events import (
    PostCollabRequestCreatedEvent,
    PostCollabRequestDeletedEvent,
    PostCommentCreatedEvent,
    PostCommentDeletedEvent,
    PostCommentLikeCreatedEvent,
    PostCommentLikeDeletedEvent,
    PostLikeCreatedEvent,
    PostLikeDeletedEvent,
    PostShareCreatedEvent,
    PostShareDeletedEvent,
    PostViewedEvent,
)


class PostShareCreatedHandler:
    def __init__(self, create_post_share_usecase: CreatePostShareUsecase):
        self.create_post_share_usecase = create_post_share_usecase

    async def handle(self, event: PostShareCreatedEvent) -> str:
        command = CreatePostShareCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
        )
        self.create_post_share_usecase.execute(command)
        return event.__class__.__name__


class PostShareDeletedHandler:
    def __init__(self, delete_post_share_usecase: DeletePostShareUsecase):
        self.delete_post_share_usecase = delete_post_share_usecase

    async def handle(self, event: PostShareDeletedEvent) -> str:
        command = DeletePostShareCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
        )
        self.delete_post_share_usecase.execute(command)
        return event.__class__.__name__


class PostCollabRequestCreatedHandler:
    def __init__(self, create_post_collab_request_usecase: CreatePostCollabRequestUsecase):
        self.create_post_collab_request_usecase = create_post_collab_request_usecase

    async def handle(self, event: PostCollabRequestCreatedEvent) -> str:
        command = CreatePostCollabRequestCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
        )
        self.create_post_collab_request_usecase.execute(command)
        return event.__class__.__name__


class PostCollabRequestDeletedHandler:
    def __init__(self, delete_post_collab_request_usecase: DeletePostCollabRequestUsecase):
        self.delete_post_collab_request_usecase = delete_post_collab_request_usecase

    async def handle(self, event: PostCollabRequestDeletedEvent) -> str:
        command = DeletePostCollabRequestCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
        )
        self.delete_post_collab_request_usecase.execute(command)
        return event.__class__.__name__


class PostCommentCreatedHandler:
    def __init__(self, create_post_comment_usecase: CreatePostCommentUsecase):
        self.create_post_comment_usecase = create_post_comment_usecase

    async def handle(self, event: PostCommentCreatedEvent) -> str:
        command = CreatePostCommentCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
        )
        self.create_post_comment_usecase.execute(command)
        return event.__class__.__name__


class PostCommentDeletedHandler:
    def __init__(self, delete_post_comment_usecase: DeletePostCommentUsecase):
        self.delete_post_comment_usecase = delete_post_comment_usecase

    async def handle(self, event: PostCommentDeletedEvent) -> str:
        command = DeletePostCommentCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
        )
        self.delete_post_comment_usecase.execute(command)
        return event.__class__.__name__


class PostCommentLikeCreatedHandler:
    def __init__(self, create_post_comment_like_usecase: CreatePostCommentLikeUsecase):
        self.create_post_comment_like_usecase = create_post_comment_like_usecase

    async def handle(self, event: PostCommentLikeCreatedEvent) -> str:
        command = CreatePostCommentLikeCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
        )
        self.create_post_comment_like_usecase.execute(command)
        return event.__class__.__name__


class PostCommentLikeDeletedHandler:
    def __init__(self, delete_post_comment_like_usecase: DeletePostCommentLikeUsecase):
        self.delete_post_comment_like_usecase = delete_post_comment_like_usecase

    async def handle(self, event: PostCommentLikeDeletedEvent) -> str:
        command = DeletePostCommentLikeCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
        )
        self.delete_post_comment_like_usecase.execute(command)
        return event.__class__.__name__


class PostLikeCreatedHandler:
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
            created_at=event.createdAt,
        )
        self.create_post_like_usecase.execute(command)
        return event.__class__.__name__


class PostLikeDeletedHandler:
    def __init__(self, delete_post_like_usecase: DeletePostLikeUsecase):
        self.delete_post_like_usecase = delete_post_like_usecase

    async def handle(self, event: PostLikeDeletedEvent) -> str:
        command = DeletePostLikeCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
            post_id=event.postId,
            user_id=event.userId,
            source=event.source,
            feed_position=event.feedPosition,
        )
        self.delete_post_like_usecase.execute(command)
        return event.__class__.__name__


class PostViewedHandler:
    def __init__(self, create_post_view_usecase: CreatePostViewUsecase):
        self.create_post_view_usecase = create_post_view_usecase

    async def handle(self, event: PostViewedEvent) -> str:
        command = CreatePostViewCommand(
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
        self.create_post_view_usecase.execute(command)
        return event.__class__.__name__
