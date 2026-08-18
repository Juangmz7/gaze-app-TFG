import uuid
from datetime import datetime

class Follow:
    def __init__(self, follower_id: uuid.UUID, followed_id: uuid.UUID, created_at: datetime):
        self.follower_id = follower_id
        self.followed_id = followed_id