from datetime import datetime
from enum import Enum
from typing import Optional
from uuid import UUID

from rabbitmq.model.event_message import EventMessage


class InteractionSource(str, Enum):
    HOME_FEED = "HOME_FEED"
    USER_PROFILE = "USER_PROFILE"
    SEARCH = "SEARCH"

class PostViewExitReason(str, Enum):
    SCROLL_NEXT = "SCROLL_NEXT"
    VIDEO_COMPLETED = "VIDEO_COMPLETED"
    APP_BACKGROUNDED = "APP_BACKGROUNDED"
    NAVIGATED_AWAY = "NAVIGATED_AWAY"


class PostType(str, Enum):
    BASIC = "BASIC"
    COLAB = "COLAB"


class CollabMemberStatus(str, Enum):
    PENDING = "PENDING"
    ACCEPTED = "ACCEPTED"
    REJECTED = "REJECTED"
    DELETED = "DELETED"
    LEFT = "LEFT"
    BANNED = "BANNED"


class CollabMemberRole(str, Enum):
    ADMIN = "ADMIN"
    MEMBER = "MEMBER"


class PostShareDeletedEvent(EventMessage):
    postId: UUID
    userId: UUID

class PostShareCreatedEvent(EventMessage):
    postId: UUID
    userId: UUID
    createdAt: datetime


class PostCollabRequestCreatedEvent(EventMessage):
    collabId: UUID
    userId: UUID
    status: CollabMemberStatus
    role: CollabMemberRole
    createdAt: datetime


class PostCollabRequestDeletedEvent(EventMessage):
    collabId: UUID
    userId: UUID
    deletedBy: UUID
    collabMemberStatus: CollabMemberStatus
    role: CollabMemberRole
    memberCreatedAt: datetime


class PostCommentLikeDeletedEvent(EventMessage):
    commentId: UUID
    userId: UUID
    source: InteractionSource
    feedPosition: int


class PostCommentLikeCreatedEvent(EventMessage):
    postId: UUID
    commentId: UUID
    userId: UUID
    source: InteractionSource
    feedPosition: int
    createdAt: datetime


class PostCommentDeletedEvent(EventMessage):
    commentId: UUID
    postId: UUID
    userId: UUID


class PostCommentCreatedEvent(EventMessage):
    commentId: UUID
    postId: UUID
    userId: UUID
    content: str
    replyTo: Optional[UUID] = None
    createdAt: datetime
    updatedAt: datetime


class PostViewedEvent(EventMessage):
    viewId: UUID
    postId: UUID
    userId: UUID
    source: PostViewSource
    feedPosition: int
    durationMs: int
    timeWatchedMs: int
    completionPercent: int
    exitReason: PostViewExitReason
    serverTimestamp: datetime
    replayCount: int


class PostLikeDeletedEvent(EventMessage):
    postId: UUID
    userId: UUID
    source: InteractionSource
    feedPosition: int


class PostLikeCreatedEvent(EventMessage):
    postId: UUID
    userId: UUID
    source: InteractionSource
    feedPosition: int
    createdAt: datetime


class PostBannedEvent(EventMessage):
    pass


class PostDeletedEvent(EventMessage):
    postId: UUID
    userId: UUID


class PostUpdatedEvent(EventMessage):
    postId: UUID
    userId: UUID
    collabId: Optional[UUID] = None
    postType: PostType
    description: Optional[str] = None
    taggedUsers: set[str] = set()
    postTags: set[str] = set()
    createdAt: datetime
    updatedAt: datetime


class PostCreatedEvent(EventMessage):
    postId: UUID
    userId: UUID
    collabId: Optional[UUID] = None
    postType: PostType
    description: Optional[str] = None
    taggedUsers: set[str] = set()
    postTags: set[str] = set()
    createdAt: datetime
    updatedAt: datetime


class PostFeedExhaustedEvent(EventMessage):
    pass
