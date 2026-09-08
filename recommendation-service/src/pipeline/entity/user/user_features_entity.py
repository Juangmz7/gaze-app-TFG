from datetime import datetime
from uuid import UUID

from sqlalchemy import DateTime, JSON
from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy.types import Uuid

from shared.entity.base import Base


class UserFeaturesRecord(Base):
    __tablename__ = "user_features"

    user_id: Mapped[UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True)
    semantic_embedding: Mapped[list[float]] = mapped_column(JSON, nullable=False)
    last_updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
