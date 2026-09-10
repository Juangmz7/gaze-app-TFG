from datetime import datetime
from uuid import UUID

from sqlalchemy import DateTime
from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy.types import Uuid

from shared.entity.base import Base


class BlockRecord(Base):
    __tablename__ = "blocks"

    blocker_id: Mapped[UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True)
    blocked_id: Mapped[UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
