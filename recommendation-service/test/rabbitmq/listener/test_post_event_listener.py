from datetime import datetime, timezone
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock
from uuid import uuid4

import pytest

from rabbitmq.config.constants import PostRoutingKey
from rabbitmq.event.post.post_events import InteractionSource, PostLikeCreatedEvent
from rabbitmq.exception.exceptions import RejectAndDontRequeueError
from rabbitmq.listener.post_event_listener import PostEventListener
from rabbitmq.model.event_message import EventMessage
from shared.repository.processed_events_repository import ProcessedEventsRepository


pytestmark = pytest.mark.unit


def _message(routing_key: str):
    return SimpleNamespace(raw_message=SimpleNamespace(routing_key=routing_key))


def _event() -> PostLikeCreatedEvent:
    return PostLikeCreatedEvent(
        id=uuid4(),
        correlationId=uuid4(),
        occurredAt=datetime(2026, 1, 1, tzinfo=timezone.utc),
        postId=uuid4(),
        userId=uuid4(),
        source=InteractionSource.HOME_FEED,
        feedPosition=1,
    )


@pytest.mark.asyncio
async def test_handle_event_dispatches_to_handler():
    # Arrange
    repository = Mock(spec=ProcessedEventsRepository)
    repository.isAlreadyProcessed.return_value = False
    handler = SimpleNamespace(handle=AsyncMock(return_value="PostLikeCreatedEvent"))
    listener = PostEventListener(
        repository,
        {PostRoutingKey.LIKE_CREATED: (PostLikeCreatedEvent, handler)},
    )
    event = _event()

    # Act
    await listener.handle_event(event, _message(PostRoutingKey.LIKE_CREATED.value))

    # Assert
    handler.handle.assert_awaited_once()


@pytest.mark.asyncio
async def test_handle_event_marks_processed():
    # Arrange
    repository = Mock(spec=ProcessedEventsRepository)
    repository.isAlreadyProcessed.return_value = False
    handler = SimpleNamespace(handle=AsyncMock(return_value="PostLikeCreatedEvent"))
    listener = PostEventListener(
        repository,
        {PostRoutingKey.LIKE_CREATED: (PostLikeCreatedEvent, handler)},
    )
    event = _event()

    # Act
    await listener.handle_event(event, _message(PostRoutingKey.LIKE_CREATED.value))

    # Assert
    repository.setEventAsProcessed.assert_called_once_with(
        correlation_id=event.correlationId,
        event_id=event.id,
        event_name="PostLikeCreatedEvent",
    )


@pytest.mark.asyncio
async def test_duplicate_event_does_not_invoke_handler():
    # Arrange
    repository = Mock(spec=ProcessedEventsRepository)
    repository.isAlreadyProcessed.return_value = True
    handler = SimpleNamespace(handle=AsyncMock(return_value="PostLikeCreatedEvent"))
    listener = PostEventListener(
        repository,
        {PostRoutingKey.LIKE_CREATED: (PostLikeCreatedEvent, handler)},
    )

    # Act
    await listener.handle_event(_event(), _message(PostRoutingKey.LIKE_CREATED.value))

    # Assert
    handler.handle.assert_not_awaited()


@pytest.mark.asyncio
async def test_duplicate_event_is_not_marked_as_processed():
    # Arrange
    repository = Mock(spec=ProcessedEventsRepository)
    repository.isAlreadyProcessed.return_value = True
    handler = SimpleNamespace(handle=AsyncMock(return_value="PostLikeCreatedEvent"))
    listener = PostEventListener(
        repository,
        {PostRoutingKey.LIKE_CREATED: (PostLikeCreatedEvent, handler)},
    )

    # Act
    await listener.handle_event(_event(), _message(PostRoutingKey.LIKE_CREATED.value))

    # Assert
    repository.setEventAsProcessed.assert_not_called()


@pytest.mark.asyncio
async def test_unknown_routing_key_is_rejected_without_requeue():
    # Arrange
    repository = Mock(spec=ProcessedEventsRepository)
    listener = PostEventListener(repository, {})

    # Act / Assert
    with pytest.raises(RejectAndDontRequeueError):
        await listener.handle_event(
            EventMessage(id=uuid4(), correlationId=uuid4(), occurredAt=datetime.now(timezone.utc)),
            _message("post.unknown"),
        )


@pytest.mark.asyncio
async def test_handler_failure_propagates_exception():
    # Arrange
    repository = Mock(spec=ProcessedEventsRepository)
    repository.isAlreadyProcessed.return_value = False
    handler = SimpleNamespace(handle=AsyncMock(side_effect=RuntimeError("boom")))
    listener = PostEventListener(
        repository,
        {PostRoutingKey.LIKE_CREATED: (PostLikeCreatedEvent, handler)},
    )

    # Act / Assert
    with pytest.raises(RuntimeError, match="boom"):
        await listener.handle_event(_event(), _message(PostRoutingKey.LIKE_CREATED.value))


@pytest.mark.asyncio
async def test_handler_failure_does_not_mark_event_processed():
    # Arrange
    repository = Mock(spec=ProcessedEventsRepository)
    repository.isAlreadyProcessed.return_value = False
    handler = SimpleNamespace(handle=AsyncMock(side_effect=RuntimeError("boom")))
    listener = PostEventListener(
        repository,
        {PostRoutingKey.LIKE_CREATED: (PostLikeCreatedEvent, handler)},
    )

    # Act
    with pytest.raises(RuntimeError, match="boom"):
        await listener.handle_event(_event(), _message(PostRoutingKey.LIKE_CREATED.value))

    # Assert
    repository.setEventAsProcessed.assert_not_called()
