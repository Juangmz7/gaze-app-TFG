import hashlib
import math
import re

from pipeline.repository.semantic_embedding_repository import SemanticEmbeddingRepository


TOKEN_PATTERN = re.compile(r"[\w#@]+")


class HashSemanticEmbeddingRepository(SemanticEmbeddingRepository):
    def __init__(self, dimensions: int = 384):
        self.dimensions = dimensions

    def embed_post(self, text: str) -> list[float]:
        vector = [0.0] * self.dimensions
        for token in TOKEN_PATTERN.findall(text.lower()):
            digest = hashlib.sha256(token.encode("utf-8")).digest()
            index = int.from_bytes(digest[:4], "big") % self.dimensions
            sign = 1.0 if digest[4] & 1 else -1.0
            vector[index] += sign

        norm = math.sqrt(sum(value * value for value in vector))
        if norm == 0:
            return vector
        return [value / norm for value in vector]
