from uuid import UUID

from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy.types import Uuid

from pipeline.entity.interaction.interaction_stats_columns import InteractionStatsColumns
from shared.entity.base import Base


class UserCreatorFeaturesRecord(InteractionStatsColumns, Base):
    __tablename__ = "user_creator_features"

    user_id: Mapped[UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True)
    creator_id: Mapped[UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True)
