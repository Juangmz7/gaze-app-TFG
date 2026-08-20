from dataclasses import dataclass
from datetime import datetime
from uuid import UUID


@dataclass(frozen=True)
class CreateBlockCommand:
    event_id: UUID
    correlation_id: UUID
    occurred_at: datetime
    blocker_user_id: UUID
    blocked_user_id: UUID


@dataclass(frozen=True)
class DeleteBlockCommand:
    event_id: UUID
    correlation_id: UUID
    occurred_at: datetime
    blocker_user_id: UUID
    blocked_user_id: UUID
