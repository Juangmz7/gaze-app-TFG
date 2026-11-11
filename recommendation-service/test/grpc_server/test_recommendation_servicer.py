import asyncio
import threading
from types import SimpleNamespace
from unittest.mock import Mock
from uuid import UUID, uuid4

import grpc
import pytest
from opentelemetry import trace

from grpc_server.recommendation_servicer import RecommendationServicer
from observability.log_context import correlation_id_ctx
from pipeline.worker.pipeline_worker_pool import PipelineWorkerPool
from recommendation.v1 import recommendation_pb2

pytestmark = pytest.mark.unit


class AbortedRpc(Exception):
    def __init__(self, code):
        self.code = code


class FakeContext:
    """The part of grpc.aio.ServicerContext the servicer uses. As in the real one,
    abort() always raises. The real one is exercised in the integration tests."""

    def __init__(self, *, time_remaining=30.0, metadata=()):
        self._time_remaining = time_remaining
        self._metadata = metadata

    def time_remaining(self):
        return self._time_remaining

    def invocation_metadata(self):
        return self._metadata

    async def abort(self, code, details=""):
        raise AbortedRpc(code)


@pytest.fixture()
async def pool():
    worker_pool = PipelineWorkerPool(1)
    yield worker_pool
    await worker_pool.shutdown()


def request(user_id=None, excluded=(), reason=recommendation_pb2.FEED_EMPTY):
    return recommendation_pb2.GetRecommendedFeedRequest(
        user_id=str(user_id or uuid4()),
        excluded_post_ids=[str(post_id) for post_id in excluded],
        reason=reason,
    )


async def test_should_return_the_ordered_ids_of_the_pipeline_as_strings(pool):
    # Arrange
    user_id, excluded = uuid4(), uuid4()
    post_ids = [uuid4(), uuid4(), uuid4()]
    usecase = Mock()
    usecase.recommend.return_value = post_ids

    # Act
    response = await RecommendationServicer(usecase, pool).GetRecommendedFeed(
        request(user_id, [excluded]), FakeContext()
    )

    # Assert
    assert list(response.post_ids) == [str(post_id) for post_id in post_ids]
    called_user_id, _, called_excluded = usecase.recommend.call_args.args
    assert called_user_id == user_id
    assert called_excluded == {excluded}


async def test_should_return_an_empty_ok_response_when_there_are_no_candidates(pool):
    usecase = Mock()
    usecase.recommend.return_value = []

    response = await RecommendationServicer(usecase, pool).GetRecommendedFeed(request(), FakeContext())

    assert list(response.post_ids) == []


@pytest.mark.parametrize(
    "bad_request",
    [
        recommendation_pb2.GetRecommendedFeedRequest(user_id="not-a-uuid"),
        recommendation_pb2.GetRecommendedFeedRequest(user_id=""),
        recommendation_pb2.GetRecommendedFeedRequest(user_id=str(uuid4()), excluded_post_ids=["not-a-uuid"]),
    ],
)
async def test_should_map_a_malformed_id_to_invalid_argument(pool, bad_request):
    usecase = Mock()

    with pytest.raises(AbortedRpc) as aborted:
        await RecommendationServicer(usecase, pool).GetRecommendedFeed(bad_request, FakeContext())

    assert aborted.value.code == grpc.StatusCode.INVALID_ARGUMENT
    usecase.recommend.assert_not_called()


async def test_should_map_an_unexpected_error_to_internal(pool):
    usecase = Mock()
    usecase.recommend.side_effect = RuntimeError("database down")

    with pytest.raises(AbortedRpc) as aborted:
        await RecommendationServicer(usecase, pool).GetRecommendedFeed(request(), FakeContext())

    assert aborted.value.code == grpc.StatusCode.INTERNAL


@pytest.mark.parametrize("time_remaining", [0.0, -1.0])
async def test_should_not_start_the_pipeline_after_the_deadline(pool, time_remaining):
    usecase = Mock()

    with pytest.raises(AbortedRpc) as aborted:
        await RecommendationServicer(usecase, pool).GetRecommendedFeed(
            request(), FakeContext(time_remaining=time_remaining)
        )

    assert aborted.value.code == grpc.StatusCode.DEADLINE_EXCEEDED
    usecase.recommend.assert_not_called()


async def test_should_run_the_pipeline_when_the_call_has_no_deadline(pool):
    usecase = Mock()
    usecase.recommend.return_value = []

    await RecommendationServicer(usecase, pool).GetRecommendedFeed(request(), FakeContext(time_remaining=None))

    usecase.recommend.assert_called_once()


async def test_should_return_resource_exhausted_above_the_concurrency_limit(pool):
    # Arrange: the only worker is busy
    release, started = threading.Event(), threading.Event()

    def busy(*args):
        started.set()
        release.wait(timeout=5)
        return []

    usecase = Mock()
    usecase.recommend.side_effect = busy
    servicer = RecommendationServicer(usecase, pool)
    first = asyncio.create_task(servicer.GetRecommendedFeed(request(), FakeContext()))
    await asyncio.to_thread(started.wait, 5)

    # Act
    with pytest.raises(AbortedRpc) as aborted:
        await servicer.GetRecommendedFeed(request(), FakeContext())

    # Assert
    assert aborted.value.code == grpc.StatusCode.RESOURCE_EXHAUSTED
    assert usecase.recommend.call_count == 1
    release.set()
    await first


async def test_should_propagate_correlation_id_and_trace_context_from_metadata_to_the_pipeline(pool):
    # Arrange
    correlation_id = uuid4()
    trace_id = "0af7651916cd43dd8448eb211c80319c"
    seen = SimpleNamespace()

    def recommend(user_id, passed_correlation_id, excluded):
        seen.thread = threading.get_ident()
        seen.log_correlation_id = correlation_id_ctx.get()
        seen.passed_correlation_id = passed_correlation_id
        seen.trace_id = format(trace.get_current_span().get_span_context().trace_id, "032x")
        return []

    usecase = Mock()
    usecase.recommend.side_effect = recommend
    context = FakeContext(metadata=(
        ("x-correlation-id", str(correlation_id)),
        ("traceparent", f"00-{trace_id}-b7ad6b7169203331-01"),
        ("binary-bin", b"\x00"),
    ))

    # Act
    await RecommendationServicer(usecase, pool).GetRecommendedFeed(request(), context)

    # Assert
    assert seen.thread != threading.get_ident()
    assert seen.log_correlation_id == str(correlation_id)
    assert seen.passed_correlation_id == str(correlation_id)
    assert seen.trace_id == trace_id
    assert correlation_id_ctx.get() is None


async def test_should_keep_the_server_span_of_the_grpc_instrumentation_as_parent(pool):
    # Arrange
    server_span = trace.NonRecordingSpan(trace.SpanContext(
        trace_id=0x4BF92F3577B34DA6A3CE929D0E0E4736, span_id=0x00F067AA0BA902B7,
        is_remote=False, trace_flags=trace.TraceFlags(trace.TraceFlags.SAMPLED)))
    seen = SimpleNamespace()

    def recommend(user_id, passed_correlation_id, excluded):
        seen.span_context = trace.get_current_span().get_span_context()
        return []

    usecase = Mock()
    usecase.recommend.side_effect = recommend
    context = FakeContext(metadata=(("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"),))

    # Act
    with trace.use_span(server_span):
        await RecommendationServicer(usecase, pool).GetRecommendedFeed(request(), context)

    # Assert
    assert seen.span_context == server_span.get_span_context()


@pytest.mark.parametrize(
    "correlation_id",
    [
        "not-a-uuid",
        "client-id_01:abc/~!",
        "019F0000-0000-7000-8000-000000000001",
        "x" * 128,
    ],
)
async def test_should_keep_any_usable_correlation_id_as_post_query_service_does(pool, correlation_id):
    # Arrange
    seen = SimpleNamespace()

    def recommend(user_id, passed_correlation_id, excluded):
        seen.passed_correlation_id = passed_correlation_id
        seen.log_correlation_id = correlation_id_ctx.get()
        return []

    usecase = Mock()
    usecase.recommend.side_effect = recommend

    # Act
    await RecommendationServicer(usecase, pool).GetRecommendedFeed(
        request(), FakeContext(metadata=(("x-correlation-id", correlation_id),))
    )

    # Assert
    assert seen.passed_correlation_id == correlation_id
    assert seen.log_correlation_id == correlation_id


@pytest.mark.parametrize(
    "metadata",
    [
        (),
        (("x-correlation-id", ""),),
        (("x-correlation-id", "x" * 129),),
        (("x-correlation-id", "with space"),),
        (("x-correlation-id", "line\nbreak"),),
        (("x-correlation-id", "del\x7f"),),
        (("x-correlation-id", "correlación"),),
    ],
)
async def test_should_generate_a_correlation_id_when_it_is_missing_or_unusable(pool, metadata):
    usecase = Mock()
    usecase.recommend.return_value = []

    await RecommendationServicer(usecase, pool).GetRecommendedFeed(request(), FakeContext(metadata=metadata))

    generated = usecase.recommend.call_args.args[1]
    assert str(UUID(generated)) == generated
