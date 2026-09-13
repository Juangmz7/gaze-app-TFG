from uuid import UUID

from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy.types import Uuid

from shared.entity.base import Base


class UserPostCommentInteractionRecord(Base):
    __tablename__ = "user_post_comment_interactions"

    comment_id: Mapped[UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True)
    user_id: Mapped[UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True)
    ever_liked: Mapped[bool] = mapped_column(default=False, nullable=False)
    ever_unliked: Mapped[bool] = mapped_column(default=False, nullable=False)
