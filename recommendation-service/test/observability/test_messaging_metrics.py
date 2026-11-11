"""FastStream's telemetry middleware on the test broker, recording through
SecondsHistogramMeter into an in-memory reader."""
import pytest
from faststream.rabbit import RabbitBroker, RabbitQueue, TestRabbitBroker
from faststream.rabbit.opentelemetry import RabbitTelemetryMiddleware
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import InMemoryMetricReader

from observability.messaging_metrics import (
    FASTSTREAM_METER_NAME,
    MESSAGING_DURATION_BUCKETS_S,
    SecondsHistogramMeter,
)

pytestmark = pytest.mark.unit

QUEUE = "q.messaging-metrics"


def metrics_by_name(reader: InMemoryMetricReader) -> dict:
    data = reader.get_metrics_data()
    return {
        metric.name: (scope_metrics.scope.name, metric)
        for resource_metrics in (data.resource_metrics if data else [])
        for scope_metrics in resource_metrics.scope_metrics
        for metric in scope_metrics.metrics
    }


async def consume_one(meter) -> None:
    broker = RabbitBroker(middlewares=[RabbitTelemetryMiddleware(meter=meter)])

    @broker.subscriber(RabbitQueue(QUEUE))
    async def handle(body: str) -> None:
        pass

    async with TestRabbitBroker(broker) as test_broker:
        await test_broker.publish("hello", queue=QUEUE)


async def test_the_process_duration_gets_bucket_boundaries_in_seconds():
    # Arrange
    reader = InMemoryMetricReader()
    provider = MeterProvider(metric_readers=[reader])

    # Act
    await consume_one(SecondsHistogramMeter(provider.get_meter(FASTSTREAM_METER_NAME)))

    # Assert: not the SDK's defaults, meant for milliseconds (0, 5, 10, 25...)
    scope, metric = metrics_by_name(reader)["messaging.process.duration"]
    point = metric.data.data_points[0]
    assert tuple(point.explicit_bounds) == MESSAGING_DURATION_BUCKETS_S
    assert point.count == 1
    assert scope == FASTSTREAM_METER_NAME


async def test_the_default_meter_keeps_faststream_s_scope_name():
    # The series must keep the scope the dashboards already see
    meter = SecondsHistogramMeter()

    assert meter.name == FASTSTREAM_METER_NAME


async def test_a_histogram_not_in_seconds_keeps_the_sdk_defaults():
    # Arrange
    reader = InMemoryMetricReader()
    meter = SecondsHistogramMeter(MeterProvider(metric_readers=[reader]).get_meter("test"))

    # Act
    meter.create_histogram("messages.size", unit="By").record(10)

    # Assert
    _, metric = metrics_by_name(reader)["messages.size"]
    assert tuple(metric.data.data_points[0].explicit_bounds) != MESSAGING_DURATION_BUCKETS_S
