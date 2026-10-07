from uuid import uuid4

import aio_pika
import pytest
from faststream.rabbit import RabbitBroker

from rabbitmq.event.feed.feed_events import RecommendedPostSentEvent
from rabbitmq.worker.outbox_relay import OutboxRelayWorker
from shared.config.database import SQLAlchemyTransactionManager
from shared.outbox.outbox_publisher import OutboxPublisher
from sqlalchemy import text


pytestmark = pytest.mark.integration

EXCHANGE = "x.outbox.test"
ROUTING_KEY = "rk.outbox.test"


async def bind_verification_queue(rabbitmq_url):
    connection = await aio_pika.connect_robust(rabbitmq_url)
    channel = await connection.channel()
    exchange = await channel.declare_exchange(EXCHANGE, aio_pika.ExchangeType.TOPIC, durable=True)
    queue = await channel.declare_queue(exclusive=True)
    await queue.bind(exchange, routing_key=ROUTING_KEY)
    return connection, queue


def add_event(db_session_factory, exchange=EXCHANGE, routing_key=ROUTING_KEY):
    event = RecommendedPostSentEvent(correlationId=uuid4(), userId=uuid4(), posts=[uuid4(), uuid4()])
    with SQLAlchemyTransactionManager(db_session_factory).transaction() as session:
        OutboxPublisher().add(
            session, event, event_type="RecommendedPostSent", exchange=exchange, routing_key=routing_key
        )
    return event


def status_of(db_session_factory, event_id):
    with db_session_factory() as session:
        return session.execute(
            text("SELECT status FROM outbox_events WHERE id = :id"), {"id": event_id}
        ).scalar_one()


async def test_relay_publishes_persistent_json_with_broker_confirm(
    rabbitmq_url, session_provider, db_session_factory
):
    # Arrange
    connection, queue = await bind_verification_queue(rabbitmq_url)
    event = add_event(db_session_factory)

    # Act
    async with RabbitBroker(rabbitmq_url) as broker:
        await OutboxRelayWorker(session_provider, broker).relay_batch()

    # Assert
    message = await queue.get(timeout=10, fail=True)
    assert message.message_id == str(event.id)
    assert message.correlation_id == str(event.correlationId)
    assert message.type == "RecommendedPostSent"
    assert message.content_type == "application/json"
    assert message.delivery_mode == aio_pika.DeliveryMode.PERSISTENT
    body = RecommendedPostSentEvent.model_validate_json(message.body)
    assert body == event
    assert b'"userId"' in message.body and b'"occurredAt"' in message.body
    assert status_of(db_session_factory, event.id) == "PROCESSED"
    await connection.close()


async def test_relay_marks_unroutable_message_processed_because_the_broker_acked_it(
    rabbitmq_url, session_provider, db_session_factory
):
    # Arrange: exchange exists, nothing is bound to this routing key
    connection, _ = await bind_verification_queue(rabbitmq_url)
    event = add_event(db_session_factory, routing_key="rk.nobody.listens")

    # Act
    async with RabbitBroker(rabbitmq_url) as broker:
        await OutboxRelayWorker(session_provider, broker).relay_batch()

    # Assert
    assert status_of(db_session_factory, event.id) == "PROCESSED"
    await connection.close()


async def test_relay_keeps_event_pending_for_unknown_exchange_and_publishes_the_next_cycle(
    rabbitmq_url, session_provider, db_session_factory
):
    # Arrange
    connection, queue = await bind_verification_queue(rabbitmq_url)
    missing = add_event(db_session_factory, exchange="x.does-not-exist")

    async with RabbitBroker(rabbitmq_url) as broker:
        worker = OutboxRelayWorker(session_provider, broker)

        # Act: first cycle fails on the missing exchange
        await worker.relay_batch()
        assert status_of(db_session_factory, missing.id) == "PENDING"

        # Act: once the event is fixed the same broker publishes it (channel recovered)
        with db_session_factory() as session:
            session.execute(
                text("UPDATE outbox_events SET exchange = :exchange WHERE id = :id"),
                {"exchange": EXCHANGE, "id": missing.id},
            )
            session.commit()
        await worker.relay_batch()

    # Assert
    assert status_of(db_session_factory, missing.id) == "PROCESSED"
    message = await queue.get(timeout=10, fail=True)
    assert message.message_id == str(missing.id)
    await connection.close()
