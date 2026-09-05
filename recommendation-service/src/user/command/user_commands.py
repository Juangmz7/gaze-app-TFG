from dataclasses import dataclass
from datetime import datetime
from typing import Optional
from uuid import UUID

from rabbitmq.event.user.user_events import UserBioEventPayload


@dataclass(frozen=True)
class DeleteUserCommand:
    event_id: UUID
    correlation_id: UUID
    occurred_at: datetime
    user_id: UUID


@dataclass(frozen=True)
class RegisterUserCommand:
    event_id: UUID
    correlation_id: UUID
    occurred_at: datetime
    user_id: UUID
    username: str
    email: str
    bio: Optional[UserBioEventPayload]


@dataclass(frozen=True)
class UpdateUserCommand:
    event_id: UUID
    correlation_id: UUID
    occurred_at: datetime
    user_id: UUID
    username: str
    email: str
    bio: Optional[UserBioEventPayload]
    picture_url: Optional[str]
    account_status: Optional[str]
    created_at: Optional[datetime]
    updated_at: Optional[datetime]
