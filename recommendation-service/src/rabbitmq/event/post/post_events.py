from datetime import datetime
from enum import Enum
from typing import Optional
from uuid import UUID

from pydantic import Field, field_validator

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


class CollabStatus(str, Enum):
    OPEN = "OPEN"
    CLOSED = "CLOSED"
    DELETED = "DELETED"


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


class PostCollabOpenedWithPostCreatedEvent(EventMessage):
    collabId: UUID
    title: str
    createdBy: UUID
    collabStatus: CollabStatus
    collabCreatedAt: datetime
    creatorMemberStatus: CollabMemberStatus
    creatorRole: CollabMemberRole
    creatorMemberCreatedAt: datetime
    postId: UUID
    userId: UUID
    postCollabId: UUID
    postType: PostType
    description: Optional[str] = None
    taggedUsers: set[str] = set()
    postTags: set[str] = set()
    postCreatedAt: datetime
    postUpdatedAt: datetime


class PostCollabOpenedForExistingPostEvent(EventMessage):
    collabId: UUID
    title: str
    createdBy: UUID
    collabStatus: CollabStatus
    collabCreatedAt: datetime
    creatorMemberStatus: CollabMemberStatus
    creatorRole: CollabMemberRole
    creatorMemberCreatedAt: datetime
    postId: UUID
    userId: UUID
    postCollabId: UUID
    postType: PostType
    description: Optional[str] = None
    taggedUsers: set[str] = set()
    postTags: set[str] = set()
    postCreatedAt: datetime
    postUpdatedAt: datetime


class PostCollabLinkedEvent(EventMessage):
    postId: UUID
    userId: UUID
    collabId: UUID
    postType: PostType
    description: Optional[str] = None
    taggedUsers: set[str] = set()
    postTags: set[str] = set()
    createdAt: datetime
    updatedAt: datetime


class PostCollabDeletedEvent(EventMessage):
    collabId: UUID
    actionedBy: UUID


class PostCollabRequestCreatedEvent(EventMessage):
    collabId: UUID
    userId: UUID
    status: CollabMemberStatus
    role: CollabMemberRole


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
    source: InteractionSource
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


class PostFeedExhaustedReason(str, Enum):
    DISCONNECT = "disconnect"
    STALE_HEARTBEAT = "stale_heartbeat"
    USER_REGISTERED = "user_registered"
    LOW_WATERMARK = "low_watermark"


class PostFeedExhaustedEvent(EventMessage):
    """UserFeedExhaustedEvent, published by post-query-service on x.feed.events."""

    userId: UUID
    reason: PostFeedExhaustedReason
    currentFeedSize: int = Field(ge=0)
    excludedPostIds: list[UUID] = []

    @field_validator("excludedPostIds", mode="before")
    @classmethod
    def _null_means_no_exclusions(cls, value: object) -> object:
        # post-query-service is written in Go, where an empty slice can marshal as null
        return [] if value is None else value
