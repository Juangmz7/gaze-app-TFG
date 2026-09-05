from dataclasses import dataclass
from datetime import datetime
from typing import Optional
from uuid import UUID

from rabbitmq.event.post.post_events import (
    InteractionSource,
    PostViewExitReason,
)


@dataclass(frozen=True)
class DeletePostShareCommand:
    event_id: UUID
    correlation_id: UUID
    occurred_at: datetime


@dataclass(frozen=True)
class CreatePostShareCommand:
    event_id: UUID
    correlation_id: UUID
    occurred_at: datetime


@dataclass(frozen=True)
class CreatePostCollabRequestCommand:
    event_id: UUID
    correlation_id: UUID
    occurred_at: datetime


@dataclass(frozen=True)
class DeletePostCollabRequestCommand:
    event_id: UUID
    correlation_id: UUID
    occurred_at: datetime


@dataclass(frozen=True)
class CreatePostCollabCommand:
    event_id: UUID
    correlation_id: UUID
    occurred_at: datetime
    collab_id: UUID
    post_id: UUID
    user_id: UUID
    created_by: UUID


@dataclass(frozen=True)
class DeletePostCollabCommand:
    event_id: UUID
    correlation_id: UUID
    occurred_at: datetime
    collab_id: UUID
    actioned_by: UUID


@dataclass(frozen=True)
class DeletePostCommentLikeCommand:
    event_id: UUID
    correlation_id: UUID
    occurred_at: datetime


@dataclass(frozen=True)
class CreatePostCommentLikeCommand:
    occurred_at: datetime
    post_id: UUID
    user_id: UUID
    source: InteractionSource
    feed_position: int
    created_at: datetime


@dataclass(frozen=True)
class DeletePostCommentCommand:
    event_id: UUID
    correlation_id: UUID
    occurred_at: datetime


@dataclass(frozen=True)
class CreatePostCommentCommand:
    event_id: UUID
    correlation_id: UUID
    occurred_at: datetime


@dataclass(frozen=True)
class RegisterPostViewCommand:
    occurred_at: datetime
    view_id: UUID
    post_id: UUID
    user_id: UUID
    source: InteractionSource
    feed_position: int
    duration_ms: int
    time_watched_ms: int
    completion_percent: int
    exit_reason: PostViewExitReason
    server_timestamp: datetime
    replay_count: int


@dataclass(frozen=True)
class DeletePostLikeCommand:
    event_id: UUID
    correlation_id: UUID
    occurred_at: datetime
    post_id: UUID
    user_id: UUID
    source: InteractionSource
    feed_position: int


@dataclass(frozen=True)
class CreatePostLikeCommand:
    occurred_at: datetime
    post_id: UUID
    user_id: UUID
    source: InteractionSource
    feed_position: int
    created_at: datetime


@dataclass(frozen=True)
class BanPostCommand:
    event_id: UUID
    correlation_id: UUID
    occurred_at: datetime


@dataclass(frozen=True)
class DeletePostCommand:
    event_id: UUID
    correlation_id: UUID
    occurred_at: datetime
    post_id: UUID
    user_id: UUID


@dataclass(frozen=True)
class UpdatePostCommand:
    event_id: UUID
    correlation_id: UUID
    occurred_at: datetime
    post_id: UUID
    user_id: UUID
    description: Optional[str]
    tagged_users: set[str]
    post_tags: set[str]
    created_at: datetime
    updated_at: datetime


@dataclass(frozen=True)
class CreatePostCommand:
    event_id: UUID
    correlation_id: UUID
    occurred_at: datetime
    post_id: UUID
    user_id: UUID
    description: Optional[str]
    tagged_users: set[str]
    post_tags: set[str]
    created_at: datetime
    updated_at: datetime


@dataclass(frozen=True)
class ExhaustPostFeedCommand:
    event_id: UUID
    correlation_id: UUID
    occurred_at: datetime
