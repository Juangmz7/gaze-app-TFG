from datetime import datetime, timezone
from uuid import UUID, uuid4

from pydantic import BaseModel, Field


class DomainEvent(BaseModel):
    """Base for events this service publishes. Field names follow the camelCase
    contract shared by every service; occurredAt is always timezone-aware so it
    serializes with an offset that Java's Instant can parse."""

    id: UUID = Field(default_factory=uuid4)
    # Propagate the triggering message's correlation id; do not generate a fresh one
    correlationId: UUID
    occurredAt: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))


class RecommendedPostSentEvent(DomainEvent):
    userId: UUID
    # Recommended post ids, best first
    posts: list[UUID]


class TrendingPostSentEvent(DomainEvent):
    # Trending post ids, best first
    posts: list[UUID]
