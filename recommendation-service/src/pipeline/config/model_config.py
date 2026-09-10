from sentence_transformers import SentenceTransformer


def load_semantic_embedding_model(
    model_name: str = "Qwen/Qwen3-Embedding-0.6B",
) -> SentenceTransformer:
    return SentenceTransformer(
        model_name,
        device="cuda"
    )
