"""The real grpc.aio server and client over a real socket, with a stub pipeline."""
import asyncio
import threading
import time
from types import SimpleNamespace
from unittest.mock import Mock
from uuid import uuid4

import grpc
import pytest
from grpc_health.v1 import health_pb2, health_pb2_grpc
from opentelemetry import trace

from _support.feed_service import free_port
from grpc_server.recommendation_grpc_server import RecommendationGrpcServer
from grpc_server.recommendation_servicer import RecommendationServicer
from observability.log_context import correlation_id_ctx
from pipeline.worker.pipeline_worker_pool import PipelineWorkerPool
from recommendation.v1 import recommendation_pb2, recommendation_pb2_grpc

pytestmark = pytest.mark.integration


class SlowToStartServicer(RecommendationServicer):
    """Reaches the real handler only after the deadline of the call has passed, as
    when a busy event loop delays it. It blocks on purpose: gRPC cancels a handler
    that yields to the loop once the deadline passes."""

    def __init__(self, *args, delay_s, reached):
        super().__init__(*args)
        self.delay_s = delay_s
        self.reached = reached

    async def GetRecommendedFeed(self, request, context):
        time.sleep(self.delay_s)
        self.reached.time_remaining = context.time_remaining()
        try:
            return await super().GetRecommendedFeed(request, context)
        finally:
            self.reached.done.set()


@pytest.fixture()
async def pool():
    worker_pool = PipelineWorkerPool(1)
    yield worker_pool
    await worker_pool.shutdown()


async def start(servicer, **options):
    port = free_port()
    server = RecommendationGrpcServer(servicer, port=port, **options)
    await server.start()
    channel = grpc.aio.insecure_channel(f"127.0.0.1:{port}")
    return server, channel, recommendation_pb2_grpc.RecommendationServiceStub(channel)


async def test_should_read_correlation_id_and_trace_context_from_real_metadata_without_a_deadline(pool):
    # Arrange
    correlation_id = "client-id-not-a-uuid"
    trace_id = "0af7651916cd43dd8448eb211c80319c"
    seen = SimpleNamespace()
    post_id = uuid4()

    def recommend(user_id, passed_correlation_id, excluded):
        seen.correlation_id = passed_correlation_id
        seen.log_correlation_id = correlation_id_ctx.get()
        seen.trace_id = format(trace.get_current_span().get_span_context().trace_id, "032x")
        seen.thread = threading.current_thread().name
        return [post_id]

    usecase = Mock()
    usecase.recommend.side_effect = recommend
    server, channel, stub = await start(RecommendationServicer(usecase, pool))

    # Act: no timeout, so the server sees no deadline at all
    response = await stub.GetRecommendedFeed(
        recommendation_pb2.GetRecommendedFeedRequest(user_id=str(uuid4()), reason=recommendation_pb2.FEED_EMPTY),
        metadata=(
            ("x-correlation-id", correlation_id),
            ("traceparent", f"00-{trace_id}-b7ad6b7169203331-01"),
        ),
    )

    # Assert
    assert list(response.post_ids) == [str(post_id)]
    assert seen.correlation_id == correlation_id
    assert seen.log_correlation_id == correlation_id
    assert seen.trace_id == trace_id
    assert seen.thread.startswith("pipeline-worker")
    await channel.close()
    await server.stop()


async def test_should_not_start_the_pipeline_once_the_deadline_of_a_real_call_has_passed(pool):
    # Arrange
    usecase = Mock()
    reached = SimpleNamespace(done=asyncio.Event())
    servicer = SlowToStartServicer(usecase, pool, delay_s=0.3, reached=reached)
    server, channel, stub = await start(servicer)

    # Act
    with pytest.raises(grpc.aio.AioRpcError) as error:
        await stub.GetRecommendedFeed(
            recommendation_pb2.GetRecommendedFeedRequest(user_id=str(uuid4())), timeout=0.05
        )
    await asyncio.wait_for(reached.done.wait(), timeout=5)

    # Assert
    assert error.value.code() == grpc.StatusCode.DEADLINE_EXCEEDED
    assert reached.time_remaining <= 0
    usecase.recommend.assert_not_called()
    await channel.close()
    await server.stop()


async def test_should_map_status_codes_over_the_wire(pool):
    usecase = Mock()
    usecase.recommend.side_effect = RuntimeError("database down")
    server, channel, stub = await start(RecommendationServicer(usecase, pool))

    with pytest.raises(grpc.aio.AioRpcError) as internal:
        await stub.GetRecommendedFeed(recommendation_pb2.GetRecommendedFeedRequest(user_id=str(uuid4())), timeout=5)
    with pytest.raises(grpc.aio.AioRpcError) as invalid:
        await stub.GetRecommendedFeed(recommendation_pb2.GetRecommendedFeedRequest(user_id="nope"), timeout=5)

    assert internal.value.code() == grpc.StatusCode.INTERNAL
    assert invalid.value.code() == grpc.StatusCode.INVALID_ARGUMENT
    await channel.close()
    await server.stop()


async def test_stop_should_let_a_call_in_flight_finish(pool):
    # Arrange: a call running in the pipeline
    release, started = threading.Event(), threading.Event()

    def recommend(*args):
        started.set()
        release.wait(timeout=10)
        return []

    usecase = Mock()
    usecase.recommend.side_effect = recommend
    server, channel, stub = await start(RecommendationServicer(usecase, pool), shutdown_grace_s=10)
    health = health_pb2_grpc.HealthStub(channel)
    assert (await health.Check(health_pb2.HealthCheckRequest())).status == health_pb2.HealthCheckResponse.SERVING
    async def call_feed():
        return await stub.GetRecommendedFeed(
            recommendation_pb2.GetRecommendedFeedRequest(user_id=str(uuid4())), timeout=15
        )

    call = asyncio.create_task(call_feed())
    await asyncio.to_thread(started.wait, 5)

    # Act
    stopping = asyncio.create_task(server.stop())
    await asyncio.sleep(0.2)
    assert not stopping.done()
    release.set()

    # Assert
    assert list((await call).post_ids) == []
    await stopping
    await channel.close()


async def test_start_should_fail_when_the_port_is_taken(pool):
    server, channel, _ = await start(RecommendationServicer(Mock(), pool))
    port = server._port

    with pytest.raises(RuntimeError):
        await RecommendationGrpcServer(RecommendationServicer(Mock(), pool), port=port).start()

    await channel.close()
    await server.stop()
