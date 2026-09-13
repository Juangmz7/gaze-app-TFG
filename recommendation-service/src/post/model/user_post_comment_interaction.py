from dataclasses import dataclass
from uuid import UUID


@dataclass
class UserPostCommentInteraction:
    comment_id: UUID
    user_id: UUID
    ever_liked: bool = False
    ever_unliked: bool = False

