from datetime import datetime, timezone
from unittest.mock import Mock
from uuid import uuid4

import pytest

from pipeline.exceptions.exceptions import DontRequeuePipelineException
from post.usecase.create_post_like_usecase import CreatePostLikeUsecase
from rabbitmq.event.post.post_events import InteractionSource, PostLikeCreatedEvent
from rabbitmq.exception.exceptions import RejectAndDontRequeueError
from rabbitmq.handler.post.post_like_created_event_handler import PostLikeCreatedEventHandler


pytestmark = pytest.mark.unit


def _event() -> PostLikeCreatedEvent:
    return PostLikeCreatedEvent(
        id=uuid4(),
        correlationId=uuid4(),
        occurredAt=datetime(2026, 1, 1, tzinfo=timezone.utc),
        postId=uuid4(),
        userId=uuid4(),
        source=InteractionSource.SEARCH,
        feedPosition=7,
    )


@pytest.mark.asyncio
async def test_handle_maps_event_to_command_and_invokes_usecase():
    # Arrange
    usecase = Mock(spec=CreatePostLikeUsecase)
    handler = PostLikeCreatedEventHandler(usecase)
    event = _event()

    # Act
    result = await handler.handle(event)

    # Assert
    assert result == "PostLikeCreatedEvent"
    command = usecase.execute.call_args.args[0]
    assert command.event_id == event.id
    assert command.correlation_id == event.correlationId
    assert command.post_id == event.postId
    assert command.user_id == event.userId
    assert command.source == event.source
    assert command.feed_position == event.feedPosition


@pytest.mark.asyncio
async def test_handle_rejects_non_requeueable_pipeline_error():
    # Arrange
    usecase = Mock(spec=CreatePostLikeUsecase)
    usecase.execute.side_effect = DontRequeuePipelineException("missing post")
    handler = PostLikeCreatedEventHandler(usecase)

    # Act / Assert
    with pytest.raises(RejectAndDontRequeueError):
        await handler.handle(_event())


@pytest.mark.asyncio
async def test_handle_propagates_unexpected_errors_for_retry_middleware():
    # Arrange
    usecase = Mock(spec=CreatePostLikeUsecase)
    usecase.execute.side_effect = RuntimeError("temporary")
    handler = PostLikeCreatedEventHandler(usecase)

    # Act / Assert
    with pytest.raises(RuntimeError, match="temporary"):
        await handler.handle(_event())
