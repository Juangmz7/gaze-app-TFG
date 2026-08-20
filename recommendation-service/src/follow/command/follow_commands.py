from dataclasses import dataclass
from datetime import datetime
from uuid import UUID


@dataclass(frozen=True)
class CreateFollowCommand:
    event_id: UUID
    correlation_id: UUID
    occurred_at: datetime
    follower_user_id: UUID
    followed_user_id: UUID


@dataclass(frozen=True)
class DeleteFollowCommand:
    event_id: UUID
    correlation_id: UUID
    occurred_at: datetime
    follower_user_id: UUID
    followed_user_id: UUID
