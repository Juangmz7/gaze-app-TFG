import json
import logging
from uuid import uuid4

import pytest
from faststream.rabbit import RabbitBroker, RabbitMessage, RabbitQueue, TestRabbitBroker

from rabbitmq.exception.exceptions import RejectAndDontRequeueError
from rabbitmq.middleware.retry_middleware import RabbitRetryMiddleware
from rabbitmq.model.event_message import EventMessage

pytestmark = pytest.mark.unit

QUEUE = "q.retry-middleware"


def valid_body() -> bytes:
    return json.dumps({
        "id": str(uuid4()),
        "correlationId": str(uuid4()),
        "occurredAt": "2026-01-01T00:00:00Z",
    }).encode()


@pytest.fixture
def attempts(monkeypatch) -> list[int]:
    """Counts the attempts of the real middleware: each one is a call to call_next."""
    monkeypatch.setattr(RabbitRetryMiddleware, "INITIAL_INTERVAL_SECONDS", 0)
    counted: list[int] = []
    original = RabbitRetryMiddleware.consume_scope

    async def consume_scope(self, call_next, msg):
        async def counted_call_next(message):
            counted.append(1)
            return await call_next(message)

        return await original(self, counted_call_next, msg)

    monkeypatch.setattr(RabbitRetryMiddleware, "consume_scope", consume_scope)
    return counted


async def consume(body: bytes, handler) -> None:
    broker = RabbitBroker(middlewares=[RabbitRetryMiddleware])
    broker.subscriber(RabbitQueue(QUEUE))(handler)
    async with TestRabbitBroker(broker) as test_broker:
        await test_broker.publish(body, queue=QUEUE, content_type="application/json")


async def ignore(event: EventMessage, message: RabbitMessage) -> None:
    pass


@pytest.mark.parametrize(
    "body",
    [
        pytest.param(b"{not json", id="not json"),
        pytest.param(b"\xff\xfe", id="not utf-8"),
        pytest.param(b'{"foo": 1}', id="missing envelope fields"),
        pytest.param(valid_body().replace(b'"id": "', b'"id": "x'), id="id not a uuid"),
    ],
)
async def test_a_malformed_payload_goes_to_the_dlq_without_retries(attempts, caplog, body):
    # Act
    with caplog.at_level(logging.ERROR):
        await consume(body, ignore)

    # Assert
    assert len(attempts) == 1
    assert "rejecting message to DLQ" in caplog.text
    assert "Retries exhausted" not in caplog.text


async def test_a_validation_error_logs_only_the_error_types_not_the_input(attempts, caplog):
    # Arrange
    secret = "do-not-log-this-value"

    # Act
    with caplog.at_level(logging.ERROR):
        await consume(json.dumps({"id": secret}).encode(), ignore)

    # Assert
    assert "Malformed event" in caplog.text
    assert "missing" in caplog.text
    assert secret not in caplog.text


async def test_a_reject_and_dont_requeue_error_goes_to_the_dlq_without_retries(attempts):
    # Arrange
    async def reject(event: EventMessage, message: RabbitMessage) -> None:
        raise RejectAndDontRequeueError("permanent")

    # Act
    await consume(valid_body(), reject)

    # Assert
    assert len(attempts) == 1


async def test_an_unexpected_failure_is_retried_twice_then_goes_to_the_dlq(attempts, caplog):
    # Arrange
    async def fail(event: EventMessage, message: RabbitMessage) -> None:
        raise RuntimeError("temporary")

    # Act
    with caplog.at_level(logging.ERROR):
        await consume(valid_body(), fail)

    # Assert: the first attempt plus 2 retries, as in the Java and Go services
    assert len(attempts) == 3
    assert "Retries exhausted after 3 attempts" in caplog.text


async def test_a_failure_that_recovers_is_processed_on_a_retry(attempts):
    # Arrange
    calls: list[int] = []

    async def flaky(event: EventMessage, message: RabbitMessage) -> None:
        calls.append(1)
        if len(calls) == 1:
            raise RuntimeError("temporary")

    # Act
    await consume(valid_body(), flaky)

    # Assert
    assert len(attempts) == 2
    assert len(calls) == 2
