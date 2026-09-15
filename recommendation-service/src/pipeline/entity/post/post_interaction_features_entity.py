from datetime import datetime
from uuid import UUID

from sqlalchemy import Float, Integer, DateTime
from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy.types import Uuid

from shared.entity.base import Base


class PostInteractionFeaturesRecord(Base):
    __tablename__ = "post_interaction_features"

    post_id: Mapped[UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True)
    impressions: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    views: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    likes: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    comments: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    shares: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    fast_skips: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    collab_requests: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    collab_requests_accepted: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    watch_time_average_percent: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    watch_time: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    last_updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    decayed_engagement_score: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
