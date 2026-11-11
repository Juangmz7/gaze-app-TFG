import asyncio
import logging
from collections.abc import Iterator
from contextlib import contextmanager
from functools import partial
from uuid import UUID, uuid4

import grpc
from opentelemetry import context as otel_context, propagate, trace

from observability.log_context import correlation_id_ctx
from pipeline.usecase.recommended_feed_use_case import RecommendedFeedUseCase
from pipeline.worker.pipeline_worker_pool import PipelineWorkerPool, PipelineWorkerPoolSaturatedError
from recommendation.v1 import recommendation_pb2, recommendation_pb2_grpc

logger = logging.getLogger(__name__)

# Defined in proto/recommendation/v1/recommendation.proto
CORRELATION_ID_METADATA_KEY = "x-correlation-id"
# Same bound as isUsableCorrelationID in post-query-service, the caller of this RPC
MAX_CORRELATION_ID_LENGTH = 128
# A rejected id is caller data of any length: keep it from flooding the logs
MAX_LOGGED_ID_LENGTH = 64


class RecommendationServicer(recommendation_pb2_grpc.RecommendationServiceServicer):
    """The status codes are part of the contract, because the client only retries
    UNAVAILABLE, DEADLINE_EXCEEDED and RESOURCE_EXHAUSTED. context.abort() raises,
    so it always stays out of the broad except below."""

    def __init__(self, recommended_feed_usecase: RecommendedFeedUseCase, worker_pool: PipelineWorkerPool):
        self.recommended_feed_usecase = recommended_feed_usecase
        self.worker_pool = worker_pool

    async def GetRecommendedFeed(
        self,
        request: recommendation_pb2.GetRecommendedFeedRequest,
        context: grpc.aio.ServicerContext,
    ) -> recommendation_pb2.GetRecommendedFeedResponse:
        metadata = _text_metadata(context)
        correlation_id = _correlation_id(metadata)

        with _request_context(metadata, correlation_id):
            try:
                user_id = UUID(request.user_id)
                excluded_post_ids = {UUID(post_id) for post_id in request.excluded_post_ids}
            except ValueError:
                logger.warning(
                    "GetRecommendedFeed rejected, malformed id: correlation_id=%s user_id=%r",
                    correlation_id, request.user_id[:MAX_LOGGED_ID_LENGTH],
                )
                await context.abort(grpc.StatusCode.INVALID_ARGUMENT, "user_id and excluded_post_ids must be UUIDs")

            logger.info(
                "GetRecommendedFeed received: correlation_id=%s user_id=%s reason=%s excluded=%s",
                correlation_id, user_id,
                _reason_name(request.reason),
                len(excluded_post_ids),
            )

            time_remaining = context.time_remaining()
            if time_remaining is not None and time_remaining <= 0:
                logger.warning(
                    "GetRecommendedFeed deadline already passed, pipeline not started: correlation_id=%s user_id=%s",
                    correlation_id, user_id,
                )
                await context.abort(grpc.StatusCode.DEADLINE_EXCEEDED, "Deadline passed before the pipeline started")

            status_code, details, post_ids = await self._run_pipeline(user_id, correlation_id, excluded_post_ids)
            if status_code is not None:
                await context.abort(status_code, details)

            logger.info(
                "GetRecommendedFeed answered: correlation_id=%s user_id=%s posts=%s",
                correlation_id, user_id, len(post_ids),
            )
            return recommendation_pb2.GetRecommendedFeedResponse(post_ids=[str(post_id) for post_id in post_ids])

    async def _run_pipeline(
        self,
        user_id: UUID,
        correlation_id: str,
        excluded_post_ids: set[UUID],
    ) -> tuple[grpc.StatusCode | None, str, list[UUID]]:
        try:
            post_ids = await self.worker_pool.try_run(
                partial(self.recommended_feed_usecase.recommend, user_id, correlation_id, excluded_post_ids)
            )
        except PipelineWorkerPoolSaturatedError:
            logger.warning(
                "GetRecommendedFeed saturated, every pipeline worker is busy: correlation_id=%s user_id=%s",
                correlation_id, user_id,
            )
            return grpc.StatusCode.RESOURCE_EXHAUSTED, "Every pipeline worker is busy", []
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.exception(
                "GetRecommendedFeed pipeline failed: correlation_id=%s user_id=%s",
                correlation_id, user_id,
            )
            return grpc.StatusCode.INTERNAL, "Recommendation pipeline failed", []
        return None, "", post_ids


def _text_metadata(context: grpc.aio.ServicerContext) -> dict[str, str]:
    return {key: value for key, value in (context.invocation_metadata() or ()) if isinstance(value, str)}


def _correlation_id(metadata: dict[str, str]) -> str:
    """Only used for logs on this transport, so it need not be a UUID: post-query-service
    keeps any usable client value, and both services must log the same one. A missing
    or unusable value must not fail the call."""
    raw_correlation_id = metadata.get(CORRELATION_ID_METADATA_KEY, "")
    return raw_correlation_id if _is_usable_correlation_id(raw_correlation_id) else str(uuid4())


def _is_usable_correlation_id(value: str) -> bool:
    """Non-empty, bounded and printable ASCII without spaces, because it ends up in logs."""
    if not value or len(value) > MAX_CORRELATION_ID_LENGTH:
        return False
    return all(" " < character < "\x7f" for character in value)


def _reason_name(reason: int) -> str:
    # A newer client can send a value this version does not know: it only feeds the logs
    if reason in recommendation_pb2.FeedRequestReason.values():
        return recommendation_pb2.FeedRequestReason.Name(reason)
    return str(reason)


@contextmanager
def _request_context(metadata: dict[str, str], correlation_id: str) -> Iterator[None]:
    """Correlation id and trace context of the caller, visible in the logs and in
    the pipeline worker, which runs with a copy of this context. Under
    opentelemetry-instrument the gRPC instrumentation has already started a server
    span that continues the caller's trace: it stays the parent. Without it (tests,
    a plain `python -m`), the caller's context is extracted here instead."""
    correlation_token = correlation_id_ctx.set(correlation_id)
    trace_token = None
    if not trace.get_current_span().get_span_context().is_valid:
        trace_token = otel_context.attach(propagate.extract(metadata))
    try:
        yield
    finally:
        if trace_token is not None:
            otel_context.detach(trace_token)
        correlation_id_ctx.reset(correlation_token)
