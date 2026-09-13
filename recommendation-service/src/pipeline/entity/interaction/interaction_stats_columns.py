from datetime import datetime

from sqlalchemy import DateTime, Float, Integer
from sqlalchemy.orm import Mapped, mapped_column


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
    raw_watch_time_average_percent: Mapped[float] = mapped_column(
        Float, nullable=False, default=0.0
    )
    raw_watch_time: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)

    decayed_impressions: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    decayed_views_engagement: Mapped[float] = mapped_column(
        Float, nullable=False, default=0.0
    )
    decayed_likes: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    decayed_comments: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    decayed_comments_likes: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    decayed_shares: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    decayed_fast_skips: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    decayed_collab_requests: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    decayed_collab_requests_accepted: Mapped[float] = mapped_column(
        Float, nullable=False, default=0.0
    )
    decayed_watch_time_average_percent: Mapped[float] = mapped_column(
        Float, nullable=False, default=0.0
    )
    decayed_watch_time: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)

    affinity_score: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    last_updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
