from sentence_transformers import SentenceTransformer

from pipeline.config.model_config import load_semantic_embedding_model


class SemanticEmbeddingModelService:
    def __init__(self):
        self.model: SentenceTransformer = load_semantic_embedding_model()

    def encode(self, text: str) -> list[float]:
        return self.model.encode(
            text,
            normalize_embeddings=True,
        ).tolist()