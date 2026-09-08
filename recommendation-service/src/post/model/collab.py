from dataclasses import dataclass
from datetime import datetime
from uuid import UUID

from rabbitmq.event.post.post_events import CollabStatus


@dataclass
class Collab:
    collab_id: UUID
    title: str
    created_by: UUID
    status: CollabStatus
    created_at: datetime

