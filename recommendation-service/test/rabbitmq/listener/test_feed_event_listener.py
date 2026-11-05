import threading
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock
from uuid import uuid4

import pytest

from _support.feed_service import feed_request_body
from pipeline.worker.pipeline_worker_pool import PipelineWorkerPool
from rabbitmq.exception.exceptions import RejectAndDontRequeueError
from rabbitmq.handler.post.post_feed_exhausted_event_handler import PostFeedExhaustedEventHandler
from rabbitmq.listener.feed_event_listener import FeedEventListener

pytestmark = pytest.mark.unit


def message(routing_key="rk.post.feed.exhausted"):
    return SimpleNamespace(raw_message=SimpleNamespace(routing_key=routing_key))


@pytest.mark.parametrize(
    "body",
    [
        b"not json",
        b"\xff\xfe",
        b"[]",
        b"{}",
        feed_request_body().replace(b'"disconnect"', b'"FEED_EMPTY"'),
        feed_request_body().replace(b'"excludedPostIds": []', b'"excludedPostIds": ["not-a-uuid"]'),
    ],
)
async def test_should_reject_a_malformed_payload_without_retries_or_calling_the_handler(body):
    handler = SimpleNamespace(handle=AsyncMock())

    with pytest.raises(RejectAndDontRequeueError):
        await FeedEventListener(handler).handle_event(body, message())

    handler.handle.assert_not_called()


async def test_should_reject_an_unknown_routing_key():
    handler = SimpleNamespace(handle=AsyncMock())

    with pytest.raises(RejectAndDontRequeueError):
        await FeedEventListener(handler).handle_event(feed_request_body(), message("rk.other"))


async def test_should_let_a_pipeline_failure_reach_the_retry_middleware():
    handler = SimpleNamespace(handle=AsyncMock(side_effect=RuntimeError("model unavailable")))

    with pytest.raises(RuntimeError):
        await FeedEventListener(handler).handle_event(feed_request_body(), message())


async def test_should_run_the_usecase_off_the_event_loop_with_the_fields_of_the_event():
    # Arrange
    user_id, correlation_id, excluded = uuid4(), uuid4(), uuid4()
    seen = SimpleNamespace()
    usecase = Mock()

    def execute(command):
        seen.thread = threading.get_ident()
        seen.command = command

    usecase.execute.side_effect = execute
    pool = PipelineWorkerPool(1)
    listener = FeedEventListener(PostFeedExhaustedEventHandler(usecase, pool))

    # Act
    await listener.handle_event(
        feed_request_body(
            user_id=user_id, correlation_id=correlation_id, excluded_post_ids=[excluded],
            reason="stale_heartbeat", current_feed_size=4,
        ),
        message(),
    )

    # Assert
    assert seen.thread != threading.get_ident()
    assert seen.command.user_id == user_id
    assert seen.command.correlation_id == correlation_id
    assert seen.command.excluded_post_ids == (excluded,)
    assert seen.command.reason.value == "stale_heartbeat"
    assert seen.command.current_feed_size == 4
    await pool.shutdown()
