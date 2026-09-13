from uuid import UUID

from sqlalchemy import String
from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy.types import Uuid

from pipeline.entity.interaction.interaction_stats_columns import InteractionStatsColumns
from shared.entity.base import Base


class PostTagFeaturesRecord(InteractionStatsColumns, Base):
    __tablename__ = "post_tag_features"

    user_id: Mapped[UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True)
    tag_name: Mapped[str] = mapped_column(String(128), primary_key=True)
