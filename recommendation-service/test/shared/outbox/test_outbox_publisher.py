from uuid import uuid4

import pytest

from rabbitmq.event.feed.feed_events import TrendingPostSentEvent
from shared.outbox.outbox_publisher import OutboxPublisher
from shared.repository.outbox_event_repository import OutboxEventRepository


pytestmark = pytest.mark.unit


class InMemoryOutboxEventRepository(OutboxEventRepository):
    def __init__(self):
        self.added = []

    def add(self, event):
        self.added.append(event)

    def claim_next(self, locked_before):
        raise NotImplementedError

    def mark_processed(self, event_id):
        raise NotImplementedError

    def mark_failed_attempt(self, event_id, attempts, status, last_error):
        raise NotImplementedError

    def delete_processed_before(self, processed_before):
        raise NotImplementedError


def test_add_stores_the_event_with_its_destination_and_camel_case_payload():
    # Arrange
    repository = InMemoryOutboxEventRepository()
    event = TrendingPostSentEvent(correlationId=uuid4(), posts=[uuid4()])

    # Act
    OutboxPublisher(repository).add(
        event, event_type="TrendingPostSent", exchange="x.feed.events", routing_key="rk.post.trending.sent"
    )

    # Assert
    [stored] = repository.added
    assert stored.id == event.id
    assert stored.correlation_id == event.correlationId
    assert stored.event_type == "TrendingPostSent"
    assert stored.exchange == "x.feed.events"
    assert stored.routing_key == "rk.post.trending.sent"
    assert stored.attempts == 0
    assert stored.payload == event.model_dump(mode="json")
    assert set(stored.payload) == {"id", "correlationId", "occurredAt", "posts"}
