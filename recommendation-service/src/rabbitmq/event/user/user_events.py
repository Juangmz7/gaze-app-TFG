from datetime import datetime
from typing import Optional
from uuid import UUID

from pydantic import BaseModel

from rabbitmq.model.event_message import EventMessage


class UserBioEventPayload(BaseModel):
    description: Optional[str] = None
    socialMedia: dict[str, str] = {}


class UserFollowDeletedEvent(EventMessage):
    followerUserId: UUID
    followedUserId: UUID


class UserFollowCreatedEvent(EventMessage):
    followerUserId: UUID
    followedUserId: UUID


class UserBlockDeletedEvent(EventMessage):
    blockerUserId: UUID
    blockedUserId: UUID


class UserDeletedEvent(EventMessage):
    userId: UUID


class UserBlockCreatedEvent(EventMessage):
    blockerUserId: UUID
    blockedUserId: UUID


class UserRegisteredEvent(EventMessage):
    userId: UUID
    username: str
    email: str
    bio: Optional[UserBioEventPayload] = None


class UserUpdatedEvent(EventMessage):
    userId: UUID
    username: str
    email: str
    bio: Optional[UserBioEventPayload] = None
    pictureUrl: Optional[str] = None
    accountStatus: Optional[str] = None
    createdAt: Optional[datetime] = None
    updatedAt: Optional[datetime] = None