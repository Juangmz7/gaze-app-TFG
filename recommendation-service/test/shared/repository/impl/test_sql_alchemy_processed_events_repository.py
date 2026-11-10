from uuid import uuid4

import pytest

from shared.repository.impl.sql_alchemy_processed_events_repository import (
    SqlAlchemyProcessedEventsRepository,
)


pytestmark = pytest.mark.integration


def test_processed_event_repository_detects_unprocessed_events(session_provider):
    # Arrange
    repository = SqlAlchemyProcessedEventsRepository(session_provider)
    event_id = uuid4()
    correlation_id = uuid4()

    # Act
    is_processed = repository.isAlreadyProcessed(event_id, correlation_id)

    # Assert
    assert is_processed is False


def test_processed_event_repository_marks_events_as_processed(session_provider):
    # Arrange
    repository = SqlAlchemyProcessedEventsRepository(session_provider)
    event_id = uuid4()
    correlation_id = uuid4()

    # Act
    repository.setEventAsProcessed(event_id, correlation_id, "PostLikeCreatedEvent")
    is_processed = repository.isAlreadyProcessed(event_id, correlation_id)

    # Assert
    assert is_processed is True


def test_processed_event_upsert_is_idempotent(session_provider):
    # Arrange
    repository = SqlAlchemyProcessedEventsRepository(session_provider)
    event_id = uuid4()
    correlation_id = uuid4()

    # Act
    repository.setEventAsProcessed(event_id, correlation_id, "First")
    repository.setEventAsProcessed(event_id, correlation_id, "Second")

    # Assert
    assert repository.isAlreadyProcessed(event_id, correlation_id) is True


def test_mark_as_processed_if_absent_inserts_only_once(session_provider):
    # Arrange
    repository = SqlAlchemyProcessedEventsRepository(session_provider)
    event_id, correlation_id = uuid4(), uuid4()

    # Act
    first = repository.markAsProcessedIfAbsent(event_id=event_id, correlation_id=correlation_id, event_name="Event")
    second = repository.markAsProcessedIfAbsent(event_id=event_id, correlation_id=correlation_id, event_name="Event")

    # Assert
    assert first is True
    assert second is False
    assert repository.isAlreadyProcessed(event_id=event_id, correlation_id=correlation_id) is True
