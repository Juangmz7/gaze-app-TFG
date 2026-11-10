from contextlib import contextmanager
from datetime import datetime, timezone
from unittest.mock import Mock
from uuid import uuid4

import pytest

from pipeline.usecase.recommended_feed_use_case import RecommendedFeedUseCase
from post.command.post_commands import ExhaustPostFeedCommand
from post.usecase.exhaust_post_feed_usecase import ExhaustPostFeedUsecase
from rabbitmq.event.post.post_events import PostFeedExhaustedReason
from shared.repository.processed_events_repository import ProcessedEventsRepository
from shared.transaction.transaction_manager import TransactionManager

pytestmark = pytest.mark.unit


class RecordingTransactionManager(TransactionManager):
    def __init__(self, journal):
        self.journal = journal
        self.active = False

    @contextmanager
    def transaction(self):
        self.journal.append("begin")
        self.active = True
        try:
            yield
        finally:
            self.active = False
            self.journal.append("commit")


def command(**overrides):
    values = {
        "event_id": uuid4(),
        "correlation_id": uuid4(),
        "occurred_at": datetime(2026, 11, 4, tzinfo=timezone.utc),
        "user_id": uuid4(),
        "reason": PostFeedExhaustedReason.DISCONNECT,
        "current_feed_size": 2,
        "excluded_post_ids": (uuid4(),),
    }
    values.update(overrides)
    return ExhaustPostFeedCommand(**values)


@pytest.fixture()
def journal():
    return []


@pytest.fixture()
def transaction_manager(journal):
    return RecordingTransactionManager(journal)


@pytest.fixture()
def processed_events(journal):
    repository = Mock(spec=ProcessedEventsRepository)
    repository.isAlreadyProcessed.return_value = False
    repository.markAsProcessedIfAbsent.side_effect = lambda **_: journal.append("mark") or True
    return repository


@pytest.fixture()
def orchestrator(journal, transaction_manager):
    pipeline = Mock()

    def recommend(user_id, excluded_post_ids):
        journal.append(f"pipeline in_transaction={transaction_manager.active}")
        return pipeline.posts

    pipeline.posts = [uuid4(), uuid4()]
    pipeline.recommend.side_effect = recommend
    return pipeline


@pytest.fixture()
def outbox(journal):
    publisher = Mock()
    publisher.add.side_effect = lambda *_, **__: journal.append("outbox")
    return publisher


@pytest.fixture()
def usecase(orchestrator, outbox, transaction_manager, processed_events):
    return ExhaustPostFeedUsecase(
        RecommendedFeedUseCase(orchestrator, outbox, transaction_manager),
        processed_events,
    )


def test_should_run_the_pipeline_outside_the_transaction_that_writes_the_outbox_event(usecase, journal):
    usecase.execute(command())

    assert journal == ["pipeline in_transaction=False", "begin", "mark", "outbox", "commit"]


def test_should_acknowledge_a_duplicate_without_running_the_pipeline_or_publishing(
    usecase, processed_events, orchestrator, outbox
):
    processed_events.isAlreadyProcessed.return_value = True

    usecase.execute(command())

    orchestrator.recommend.assert_not_called()
    outbox.add.assert_not_called()


def test_should_publish_nothing_when_another_replica_won_the_same_event(usecase, processed_events, outbox):
    processed_events.markAsProcessedIfAbsent.side_effect = None
    processed_events.markAsProcessedIfAbsent.return_value = False

    usecase.execute(command())

    outbox.add.assert_not_called()


def test_should_publish_with_the_correlation_id_of_the_request_and_pass_the_exclusions(usecase, orchestrator, outbox):
    # Arrange
    requested = command()

    # Act
    usecase.execute(requested)

    # Assert
    orchestrator.recommend.assert_called_once_with(requested.user_id, requested.excluded_post_ids)
    event = outbox.add.call_args.args[0]
    assert event.correlationId == requested.correlation_id
    assert event.userId == requested.user_id
    assert event.posts == orchestrator.posts
    assert event.id != requested.event_id
    assert outbox.add.call_args.kwargs == {
        "event_type": "RecommendedPostSent",
        "exchange": "x.feed.events",
        "routing_key": "rk.post.feed.recommended.sent",
    }


def test_should_publish_also_for_an_empty_result(usecase, orchestrator, outbox):
    orchestrator.posts = []
    requested = command()

    usecase.execute(requested)

    event = outbox.add.call_args.args[0]
    assert event.posts == []
    assert event.correlationId == requested.correlation_id


def test_should_not_mark_the_event_processed_when_the_pipeline_fails(usecase, orchestrator, processed_events, outbox):
    orchestrator.recommend.side_effect = RuntimeError("model unavailable")

    with pytest.raises(RuntimeError):
        usecase.execute(command())

    processed_events.markAsProcessedIfAbsent.assert_not_called()
    outbox.add.assert_not_called()
