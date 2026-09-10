from datetime import datetime
from uuid import UUID

from sqlalchemy import DateTime, String
from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy.types import Uuid

from shared.entity.base import Base


class CollabRecord(Base):
    __tablename__ = "collabs"

    collab_id: Mapped[UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True)
    title: Mapped[str] = mapped_column(String(512), nullable=False)
    created_by: Mapped[UUID] = mapped_column(Uuid(as_uuid=True), nullable=False)
    status: Mapped[str] = mapped_column(String(64), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
