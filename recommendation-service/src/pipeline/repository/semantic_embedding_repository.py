from abc import ABC, abstractmethod


class SemanticEmbeddingRepository(ABC):
    @abstractmethod
    def embed_post(self, text: str) -> list[float]:
        pass
