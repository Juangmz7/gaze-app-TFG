from uuid import UUID

from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy.types import Uuid

from shared.entity.base import Base


class CommentPostRecord(Base):
    __tablename__ = "comment_posts"

    comment_id: Mapped[UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True)
    post_id: Mapped[UUID] = mapped_column(Uuid(as_uuid=True), nullable=False)
