
from datetime import datetime
from uuid import UUID

class Block:
    def __init__(self, blocker_id: UUID, blocked_id: UUID, created_at: datetime):
        self.blocker_id = blocker_id
        self.blocked_id = blocked_id
        self.created_at = created_at