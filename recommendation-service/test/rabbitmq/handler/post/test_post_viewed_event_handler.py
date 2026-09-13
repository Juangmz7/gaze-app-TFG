from datetime import datetime, timezone
from unittest.mock import Mock
from uuid import uuid4

import pytest

from post.usecase.register_post_view_usecase import RegisterPostViewUsecase
from rabbitmq.event.post.post_events import (
    InteractionSource,
    PostViewExitReason,
    PostViewedEvent,
)
from rabbitmq.handler.post.post_viewed_event_handler import PostViewedEventHandler


pytestmark = pytest.mark.unit


@pytest.mark.asyncio
async def test_handle_maps_view_event_to_register_view_command():
    # Arrange
    usecase = Mock(spec=RegisterPostViewUsecase)
    handler = PostViewedEventHandler(usecase)
    event = PostViewedEvent(
        id=uuid4(),
        correlationId=uuid4(),
        occurredAt=datetime(2026, 1, 1, tzinfo=timezone.utc),
        viewId=uuid4(),
        postId=uuid4(),
        userId=uuid4(),
        source=InteractionSource.HOME_FEED,
        feedPosition=2,
        durationMs=10000,
        timeWatchedMs=7500,
        completionPercent=75,
        exitReason=PostViewExitReason.SCROLL_NEXT,
        serverTimestamp=datetime(2026, 1, 1, tzinfo=timezone.utc),
        replayCount=1,
    )

    # Act
    result = await handler.handle(event)

    # Assert
    assert result == "PostViewedEvent"
    command = usecase.execute.call_args.args[0]
    assert command.view_id == event.viewId
    assert command.duration_ms == 10000
    assert command.time_watched_ms == 7500
    assert command.completion_percent == 75
    assert command.exit_reason is PostViewExitReason.SCROLL_NEXT
