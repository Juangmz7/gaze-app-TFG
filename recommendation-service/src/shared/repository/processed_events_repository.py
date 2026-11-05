from abc import ABC, abstractmethod
from uuid import UUID


class ProcessedEventsRepository(ABC):
    @abstractmethod
    def isAlreadyProcessed(self, event_id: UUID, correlation_id: UUID) -> bool:
        pass

    @abstractmethod
    def setEventAsProcessed(self, event_id: UUID, correlation_id: UUID, event_name: str) -> None:
        pass

    @abstractmethod
    def markAsProcessedIfAbsent(self, event_id: UUID, correlation_id: UUID, event_name: str) -> bool:
        """Inserts the row unless it exists. Returns whether this call inserted it."""
        pass
