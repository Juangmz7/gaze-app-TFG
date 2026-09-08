from datetime import datetime
from uuid import UUID

from sqlalchemy import DateTime, Float, Index, Integer, JSON, String
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column
from sqlalchemy.types import Uuid


class Base(DeclarativeBase):
    pass


class FollowRecord(Base):
    __tablename__ = "follows"

    follower_id: Mapped[UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True)
    followed_id: Mapped[UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class BlockRecord(Base):
    __tablename__ = "blocks"

    blocker_id: Mapped[UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True)
    blocked_id: Mapped[UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class ProcessedEventRecord(Base):
    __tablename__ = "processed_events"

    event_id: Mapped[UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True)
    correlation_id: Mapped[UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True)
    event_name: Mapped[str] = mapped_column(String(255), nullable=False)
    processed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class CollabRecord(Base):
    __tablename__ = "collabs"

    collab_id: Mapped[UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True)
    title: Mapped[str] = mapped_column(String(512), nullable=False)
    created_by: Mapped[UUID] = mapped_column(Uuid(as_uuid=True), nullable=False)
    status: Mapped[str] = mapped_column(String(64), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class CommentPostRecord(Base):
    __tablename__ = "comment_posts"

    comment_id: Mapped[UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True)
    post_id: Mapped[UUID] = mapped_column(Uuid(as_uuid=True), nullable=False)


class PostFeaturesRecord(Base):
    __tablename__ = "post_features"
    __table_args__ = (
        Index("ix_post_features_collab_id", "collab_id"),
    )

    post_id: Mapped[UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True)
    creator_id: Mapped[UUID] = mapped_column(Uuid(as_uuid=True), nullable=False)
    collab_id: Mapped[UUID | None] = mapped_column(Uuid(as_uuid=True), nullable=True)
    collab_title: Mapped[str | None] = mapped_column(String(512), nullable=True)
    description: Mapped[str | None] = mapped_column(String, nullable=True)
    tags: Mapped[list[str]] = mapped_column(JSON, nullable=False)
    tagged_users_ids: Mapped[list[str]] = mapped_column(JSON, nullable=False)
    semantic_embedding: Mapped[list[float]] = mapped_column(JSON, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class UserPostInteractionsRecord(Base):
    __tablename__ = "user_post_interactions"

    post_id: Mapped[UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True)
    user_id: Mapped[UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True)
    ever_liked: Mapped[bool] = mapped_column(default=False, nullable=False)
    ever_unliked: Mapped[bool] = mapped_column(default=False, nullable=False)
    ever_unshared: Mapped[bool] = mapped_column(default=False, nullable=False)
    ever_request_collab_deleted: Mapped[bool] = mapped_column(default=False, nullable=False)
    ever_shared: Mapped[bool] = mapped_column(default=False, nullable=False)
    ever_requested_collab: Mapped[bool] = mapped_column(default=False, nullable=False)
    comment_count: Mapped[int] = mapped_column(Integer, default=0, nullable=False)


class UserPostCommentInteractionRecord(Base):
    __tablename__ = "user_post_comment_interactions"

    comment_id: Mapped[UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True)
    user_id: Mapped[UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True)
    ever_liked: Mapped[bool] = mapped_column(default=False, nullable=False)
    ever_unliked: Mapped[bool] = mapped_column(default=False, nullable=False)


class UserFeaturesRecord(Base):
    __tablename__ = "user_features"

    user_id: Mapped[UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True)
    semantic_embedding: Mapped[list[float]] = mapped_column(JSON, nullable=False)
    last_updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class InteractionStatsColumns:
    raw_impressions: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    raw_views: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    raw_likes: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    raw_comments: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    raw_comments_likes: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    raw_shares: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    raw_fast_skips: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    raw_collab_requests: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    raw_collab_requests_accepted: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    raw_watch_time_average_percent: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    raw_watch_time: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)

    decayed_impressions: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    decayed_views_engagement: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    decayed_likes: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    decayed_comments: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    decayed_comments_likes: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    decayed_shares: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    decayed_fast_skips: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    decayed_collab_requests: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    decayed_collab_requests_accepted: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    decayed_watch_time_average_percent: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    decayed_watch_time: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)

    affinity_score: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    last_updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class UserCreatorFeaturesRecord(InteractionStatsColumns, Base):
    __tablename__ = "user_creator_features"

    user_id: Mapped[UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True)
    creator_id: Mapped[UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True)


class PostTagFeaturesRecord(InteractionStatsColumns, Base):
    __tablename__ = "post_tag_features"

    user_id: Mapped[UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True)
    tag_name: Mapped[str] = mapped_column(String(128), primary_key=True)
