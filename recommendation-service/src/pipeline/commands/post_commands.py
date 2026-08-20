from dataclasses import dataclass
from datetime import datetime
from typing import Optional
from uuid import UUID


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
class DeletePostCommand:
    event_id: UUID
    correlation_id: UUID
    occurred_at: datetime
    post_id: UUID
    user_id: UUID


@dataclass(frozen=True)
class BanPostCommand:
    event_id: UUID
    correlation_id: UUID
    occurred_at: datetime


@dataclass(frozen=True)
class FeedExhaustedCommand:
    event_id: UUID
    correlation_id: UUID
    occurred_at: datetime
