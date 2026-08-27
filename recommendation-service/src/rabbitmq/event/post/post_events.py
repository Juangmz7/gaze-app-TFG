from datetime import datetime
from enum import Enum
from typing import Optional
from uuid import UUID

from rabbitmq.model.event_message import EventMessage


class PostLikeSource(str, Enum):
    HOME_FEED = "HOME_FEED"
    USER_PROFILE = "USER_PROFILE"
    SEARCH = "SEARCH"


class PostViewSource(str, Enum):
    HOME_FEED = "HOME_FEED"
    USER_PROFILE = "USER_PROFILE"
    SEARCH = "SEARCH"


class PostViewExitReason(str, Enum):
    SCROLL_NEXT = "SCROLL_NEXT"
    VIDEO_COMPLETED = "VIDEO_COMPLETED"
    APP_BACKGROUNDED = "APP_BACKGROUNDED"
    NAVIGATED_AWAY = "NAVIGATED_AWAY"


class PostShareDeletedEvent(EventMessage):
    pass


class PostShareCreatedEvent(EventMessage):
    pass


class PostCollabRequestCreatedEvent(EventMessage):
    pass


class PostCollabRequestDeletedEvent(EventMessage):
    pass


class PostCommentLikeDeletedEvent(EventMessage):
    pass


class PostCommentLikeCreatedEvent(EventMessage):
    postId: UUID
    userId: UUID
    source: PostLikeSource
    feedPosition: int
    createdAt: datetime


class PostCommentDeletedEvent(EventMessage):
    pass


class PostCommentCreatedEvent(EventMessage):
    pass


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
    source: PostLikeSource
    feedPosition: int


class PostLikeCreatedEvent(EventMessage):
    postId: UUID
    userId: UUID
    source: PostLikeSource
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
    description: Optional[str] = None
    taggedUsers: set[str] = set()
    postTags: set[str] = set()
    createdAt: datetime
    updatedAt: datetime


class PostCreatedEvent(EventMessage):
    postId: UUID
    userId: UUID
    description: Optional[str] = None
    taggedUsers: set[str] = set()
    postTags: set[str] = set()
    createdAt: datetime
    updatedAt: datetime


class PostFeedExhaustedEvent(EventMessage):
    pass
