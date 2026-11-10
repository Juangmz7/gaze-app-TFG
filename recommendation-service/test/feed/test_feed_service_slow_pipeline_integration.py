"""The real service with a pipeline that takes seconds and a single worker: what
keeps working during a long run, and what happens above the concurrency limit."""
import asyncio
import json
import time
from uuid import uuid4

import grpc
import pytest
from grpc_health.v1 import health_pb2
from sqlalchemy import text

from _support.feed_service import (
    FEED_DLQ,
    FEED_QUEUE,
    POST_DLQ,
    POST_QUEUE,
    Amqp,
    feed_request_body,
    grpc_channel_stub,
    health_status,
    http_status,
    run_service,
)
from recommendation.v1 import recommendation_pb2

pytestmark = pytest.mark.integration

PIPELINE_DELAY_S = 4.0


@pytest.fixture(scope="module")
def slow_service(postgres_url, rabbitmq_url):
    with run_service(
        postgres_url,
        rabbitmq_url,
        TEST_PIPELINE_DELAY_S=str(PIPELINE_DELAY_S),
        PIPELINE_WORKER_POOL_SIZE="1",
    ) as running:
        yield running


@pytest.fixture()
async def amqp(slow_service, rabbitmq_url):
    client = await Amqp.connect(rabbitmq_url)
    await client.purge(FEED_QUEUE, FEED_DLQ, POST_QUEUE, POST_DLQ)
    yield client
    await client.close()


async def test_post_events_and_probes_keep_working_during_a_long_pipeline_run(
    slow_service, amqp, db_session_factory
):
    # Arrange: a long run in progress
    correlation_id = uuid4()
    started_at = time.monotonic()
    await amqp.publish(feed_request_body(correlation_id=correlation_id))

    await asyncio.sleep(0.5)  # The consumer takes it right away

    # Act: a post event while the pipeline runs
    post_id = uuid4()
    await amqp.publish(
        json.dumps({
            "id": str(uuid4()),
            "correlationId": str(uuid4()),
            "occurredAt": "2026-11-04T10:00:00Z",
            "postId": str(post_id),
            "userId": str(uuid4()),
            "postType": "BASIC",
            "description": "a post",
            "taggedUsers": [],
            "postTags": ["python"],
            "createdAt": "2026-11-04T10:00:00Z",
            "updatedAt": "2026-11-04T10:00:00Z",
        }).encode(),
        exchange="x.post.events",
        routing_key="rk.post.created",
    )

    def post_was_stored():
        with db_session_factory() as session:
            return session.execute(
                text("SELECT count(*) FROM post_features WHERE post_id = :id"), {"id": post_id}
            ).scalar_one() == 1

    while not await asyncio.to_thread(post_was_stored):
        assert time.monotonic() - started_at < PIPELINE_DELAY_S, "The post event waited for the pipeline"
        await asyncio.sleep(0.02)

    # Assert: probes and gRPC health answer, all before the pipeline finished
    assert await asyncio.to_thread(http_status, slow_service, "/healthz/live", 1.0) == 204
    assert await asyncio.to_thread(http_status, slow_service, "/healthz/ready", 1.0) == 204
    assert await health_status(slow_service) == health_pb2.HealthCheckResponse.SERVING
    assert time.monotonic() - started_at < PIPELINE_DELAY_S
    assert await amqp.drain(amqp.responses.name) == []

    # Assert: and the request is answered in the end
    response = json.loads((await amqp.next_response()).body)
    assert response["correlationId"] == str(correlation_id)


async def test_calls_above_the_concurrency_limit_get_resource_exhausted(slow_service, amqp):
    # Arrange
    channel, stub = await grpc_channel_stub(slow_service)

    async def call():
        try:
            await stub.GetRecommendedFeed(
                recommendation_pb2.GetRecommendedFeedRequest(user_id=str(uuid4())), timeout=30
            )
            return grpc.StatusCode.OK
        except grpc.aio.AioRpcError as error:
            return error.code()

    # Act: one worker, three calls at once
    started_at = time.monotonic()
    first = asyncio.create_task(call())
    await asyncio.sleep(0.5)
    excess = await asyncio.gather(call(), call())
    excess_answered_after = time.monotonic() - started_at

    # Assert: the excess is refused right away instead of queueing behind the run
    assert excess == [grpc.StatusCode.RESOURCE_EXHAUSTED, grpc.StatusCode.RESOURCE_EXHAUSTED]
    assert excess_answered_after < PIPELINE_DELAY_S
    assert await first == grpc.StatusCode.OK
    await channel.close()
