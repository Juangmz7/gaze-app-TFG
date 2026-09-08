from abc import ABC, abstractmethod
from uuid import UUID


class ProcessedEventsRepository(ABC):
    @abstractmethod
    def isAlreadyProcessed(self, event_id: UUID, correlation_id: UUID) -> bool:
        pass

    @abstractmethod
    def setEventAsProcessed(self, event_id: UUID, correlation_id: UUID, event_name: str) -> None:
        pass
