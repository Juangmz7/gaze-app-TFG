from datetime import datetime
from uuid import UUID

from pydantic import BaseModel, ConfigDict


class EventMessage(BaseModel):
    model_config = ConfigDict(
        populate_by_name=True,
        extra="allow",
    )

    id: UUID
    correlationId: UUID
    occurredAt: datetime