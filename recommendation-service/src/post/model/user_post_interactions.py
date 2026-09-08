from dataclasses import dataclass
from uuid import UUID


@dataclass
class UserPostInteractions:
    post_id: UUID
    user_id: UUID
    ever_liked: bool = False
    ever_unliked: bool = False
    ever_unshared: bool = False
    ever_request_collab_deleted: bool = False
    ever_shared: bool = False
    ever_requested_collab: bool = False
    comment_count: int = 0

