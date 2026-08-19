from uuid import UUID


class ProcessedEventsRepository:
    def __init__(self):
        pass

    def isAlreadyProcessed(self, event_id: UUID, correlation_id: UUID) -> bool:
        return False

    def setEventAsProcessed(self, event_id: UUID, correlation_id: UUID, event_name: str) -> None:
        pass