from dataclasses import dataclass
from datetime import datetime
from typing import Optional
from uuid import UUID

from rabbitmq.event.post.post_events import (
    PostLikeSource,
    PostViewExitReason,
    PostViewSource,
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
class DeletePostCommentLikeCommand:
    event_id: UUID
    correlation_id: UUID
    occurred_at: datetime


@dataclass(frozen=True)
class CreatePostCommentLikeCommand:
    event_id: UUID
    correlation_id: UUID
    occurred_at: datetime


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
    event_id: UUID
    correlation_id: UUID
    occurred_at: datetime
    view_id: UUID
    post_id: UUID
    user_id: UUID
    source: PostViewSource
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
    source: PostLikeSource
    feed_position: int


@dataclass(frozen=True)
class CreatePostLikeCommand:
    event_id: UUID
    correlation_id: UUID
    occurred_at: datetime
    post_id: UUID
    user_id: UUID
    source: PostLikeSource
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
