from datetime import datetime
from uuid import UUID

from sqlalchemy import Float, DateTime
from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy.types import Uuid

from pipeline.entity.interaction.interaction_stats_columns import InteractionStatsColumns
from shared.entity.base import Base


class PostInteractionFeaturesRecord(InteractionStatsColumns, Base):
    __tablename__ = "post_interaction_features"

    post_id: Mapped[UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True)
    decayed_engagement_score: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    
