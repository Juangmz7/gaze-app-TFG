from uuid import UUID

from sqlalchemy import Integer
from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy.types import Uuid

from shared.entity.base import Base


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
