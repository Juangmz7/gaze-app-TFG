"""Runs the real service through the real `faststream run` entrypoint, in its own
process. Only the embedding model is replaced, because the real one is a large
download that these tests do not exercise.

--print-subscribers imports the entrypoint module and prints its queues instead.
TEST_PIPELINE_DELAY_S makes every pipeline run take that long first.
"""
import json
import os
import sys
import time

import pipeline.service.semantic_embedding_model_service as embedding_model_service


class _FakeVector(list):
    def tolist(self) -> list[float]:
        return list(self)


class _FakeEmbeddingModel:
    def encode(self, text, normalize_embeddings=True):
        return _FakeVector([1.0] + [0.0] * 1023)


embedding_model_service.load_semantic_embedding_model = lambda *args, **kwargs: _FakeEmbeddingModel()


def _delay_every_pipeline_run(delay_s: float) -> None:
    from shared.config.containers import container

    orchestrator = container.recommendation_pipeline_orchestrator_usecase
    real_recommend = orchestrator.recommend

    def slow_recommend(*args, **kwargs):
        time.sleep(delay_s)
        return real_recommend(*args, **kwargs)

    orchestrator.recommend = slow_recommend


if __name__ == "__main__":
    if sys.argv[1:] == ["--print-subscribers"]:
        from rabbitmq.config.connection import broker

        print(json.dumps([subscriber.queue.name for subscriber in broker.subscribers]))
        sys.exit(0)

    if delay_s := os.getenv("TEST_PIPELINE_DELAY_S"):
        _delay_every_pipeline_run(float(delay_s))

    from faststream.__main__ import cli

    cli(["run", "rabbitmq.config.connection:app", *sys.argv[1:]])
