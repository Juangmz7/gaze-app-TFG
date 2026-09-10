from datetime import datetime
from uuid import UUID

from pgvector.sqlalchemy import Vector
from sqlalchemy import DateTime, Index, JSON, String
from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy.types import Uuid

from shared.entity.base import Base


class PostFeaturesRecord(Base):
    __tablename__ = "post_features"
    __table_args__ = (Index("ix_post_features_collab_id", "collab_id"),)

    post_id: Mapped[UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True)
    creator_id: Mapped[UUID] = mapped_column(Uuid(as_uuid=True), nullable=False)
    collab_id: Mapped[UUID | None] = mapped_column(Uuid(as_uuid=True), nullable=True)
    collab_title: Mapped[str | None] = mapped_column(String(512), nullable=True)
    description: Mapped[str | None] = mapped_column(String, nullable=True)
    tags: Mapped[list[str]] = mapped_column(JSON, nullable=False)
    tagged_users_ids: Mapped[list[str]] = mapped_column(JSON, nullable=False)
    semantic_embedding: Mapped[list[float]] = mapped_column(Vector(1024), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
