import logging
from typing import Any
from uuid import uuid4

import pytest
from faststream.rabbit import RabbitBroker, RabbitMessage, TestRabbitBroker

from observability import logging_config
from observability.log_context import correlation_id_ctx, event_id_ctx
from observability.logging_config import configure_log_context
from rabbitmq.exception.exceptions import RejectAndDontRequeueError
from rabbitmq.middleware.logging_context_middleware import LoggingContextMiddleware


pytestmark = pytest.mark.unit

QUEUE = "q.test.logging-context"


def _envelope(**extra: Any) -> dict[str, Any]:
    return {
        "id": str(uuid4()),
        "correlationId": str(uuid4()),
        "occurredAt": "2026-01-01T00:00:00Z",
        **extra,
    }


def _broker_capturing_context(seen: list[tuple[str | None, str | None]]) -> RabbitBroker:
    broker = RabbitBroker(middlewares=[LoggingContextMiddleware])

    @broker.subscriber(QUEUE)
    async def handler(body: Any) -> None:
        seen.append((correlation_id_ctx.get(), event_id_ctx.get()))

    return broker


@pytest.mark.asyncio
async def test_sets_ids_from_event_envelope():
    # Arrange
    seen: list[tuple[str | None, str | None]] = []
    broker = _broker_capturing_context(seen)
    envelope = _envelope()

    # Act
    async with TestRabbitBroker(broker) as test_broker:
        await test_broker.publish(envelope, QUEUE)

    # Assert
    assert seen == [(envelope["correlationId"], envelope["id"])]


@pytest.mark.asyncio
async def test_resets_ids_after_each_message():
    # Arrange
    seen: list[tuple[str | None, str | None]] = []
    broker = _broker_capturing_context(seen)
    first, second = _envelope(), _envelope()

    # Act
    async with TestRabbitBroker(broker) as test_broker:
        await test_broker.publish(first, QUEUE)
        await test_broker.publish(second, QUEUE)

    # Assert
    assert seen == [
        (first["correlationId"], first["id"]),
        (second["correlationId"], second["id"]),
    ]
    assert correlation_id_ctx.get() is None
    assert event_id_ctx.get() is None


@pytest.mark.asyncio
async def test_falls_back_to_amqp_correlation_id_without_envelope_ids():
    # Arrange
    seen: list[tuple[str | None, str | None]] = []
    broker = _broker_capturing_context(seen)

    # Act
    async with TestRabbitBroker(broker) as test_broker:
        await test_broker.publish({"foo": "bar"}, QUEUE, correlation_id="amqp-corr")

    # Assert
    assert seen == [("amqp-corr", None)]


@pytest.mark.asyncio
async def test_non_json_body_does_not_break_consumption():
    # Arrange
    seen: list[tuple[str | None, str | None]] = []
    broker = _broker_capturing_context(seen)

    # Act
    async with TestRabbitBroker(broker) as test_broker:
        await test_broker.publish(b"\xff not json", QUEUE, correlation_id="amqp-corr")

    # Assert
    assert seen == [("amqp-corr", None)]


@pytest.mark.asyncio
async def test_resets_ids_when_handler_fails():
    # Arrange
    broker = RabbitBroker(middlewares=[LoggingContextMiddleware])

    @broker.subscriber(QUEUE)
    async def handler(body: Any) -> None:
        raise RuntimeError("boom")

    # Act
    async with TestRabbitBroker(broker) as test_broker:
        with pytest.raises(RuntimeError):
            await test_broker.publish(_envelope(), QUEUE)

    # Assert
    assert correlation_id_ctx.get() is None
    assert event_id_ctx.get() is None


@pytest.mark.asyncio
async def test_handler_log_records_carry_ids(monkeypatch, caplog):
    # Arrange
    monkeypatch.setattr(logging_config, "_installed", False)
    previous_factory = logging.getLogRecordFactory()
    configure_log_context()
    logger = logging.getLogger("test.logging_context")
    broker = RabbitBroker(middlewares=[LoggingContextMiddleware])
    envelope = _envelope()

    @broker.subscriber(QUEUE)
    async def handler(body: Any, message: RabbitMessage) -> None:
        logger.info("handling")

    # Act
    try:
        with caplog.at_level(logging.INFO, logger="test.logging_context"):
            async with TestRabbitBroker(broker) as test_broker:
                await test_broker.publish(envelope, QUEUE)
            logger.info("after")
    finally:
        logging.setLogRecordFactory(previous_factory)

    # Assert
    handling, after = (r for r in caplog.records if r.name == "test.logging_context")
    assert handling.correlationId == envelope["correlationId"]
    assert handling.eventId == envelope["id"]
    assert not hasattr(after, "correlationId")
    assert not hasattr(after, "eventId")


@pytest.mark.asyncio
async def test_retry_middleware_logs_carry_ids_when_context_is_outermost(
    monkeypatch, caplog
):
    # Arrange: same order as rabbitmq/config/connection.py.
    pytest.importorskip("tenacity")
    from rabbitmq.middleware.retry_middleware import RabbitRetryMiddleware

    monkeypatch.setattr(logging_config, "_installed", False)
    previous_factory = logging.getLogRecordFactory()
    configure_log_context()
    broker = RabbitBroker(
        middlewares=[LoggingContextMiddleware, RabbitRetryMiddleware],
    )
    envelope = _envelope()

    @broker.subscriber(QUEUE)
    async def handler(body: Any) -> None:
        raise RejectAndDontRequeueError("bad event")

    # Act
    try:
        with caplog.at_level(logging.ERROR, logger="rabbitmq.middleware.retry_middleware"):
            async with TestRabbitBroker(broker) as test_broker:
                await test_broker.publish(envelope, QUEUE)
    finally:
        logging.setLogRecordFactory(previous_factory)

    # Assert
    (rejected,) = (
        r for r in caplog.records if r.name == "rabbitmq.middleware.retry_middleware"
    )
    assert rejected.correlationId == envelope["correlationId"]
    assert rejected.eventId == envelope["id"]
