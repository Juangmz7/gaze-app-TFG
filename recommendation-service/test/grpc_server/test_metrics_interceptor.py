"""The interceptor on a real grpc.aio server over a real socket, with the health
service as the service under measurement."""
import grpc
import pytest
from grpc_health.v1 import health, health_pb2, health_pb2_grpc
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import InMemoryMetricReader

from _support.feed_service import free_port
from grpc_server.metrics_interceptor import ServerMetricsInterceptor

pytestmark = pytest.mark.unit


def duration_points(reader: InMemoryMetricReader) -> list:
    data = reader.get_metrics_data()
    return [
        point
        for resource_metrics in (data.resource_metrics if data else [])
        for scope_metrics in resource_metrics.scope_metrics
        for metric in scope_metrics.metrics
        if metric.name == "rpc.server.call.duration"
        for point in metric.data.data_points
    ]


@pytest.fixture
def reader() -> InMemoryMetricReader:
    return InMemoryMetricReader()


@pytest.fixture
async def health_stub(reader, request):
    """A server whose only service is health; the test picks the excluded services."""
    excluded_services = getattr(request, "param", ())
    interceptor = ServerMetricsInterceptor(excluded_services, MeterProvider(metric_readers=[reader]))
    server = grpc.aio.server(interceptors=(interceptor,))
    servicer = health.aio.HealthServicer()
    health_pb2_grpc.add_HealthServicer_to_server(servicer, server)
    port = free_port()
    server.add_insecure_port(f"127.0.0.1:{port}")
    await server.start()
    await servicer.set("known", health_pb2.HealthCheckResponse.SERVING)
    channel = grpc.aio.insecure_channel(f"127.0.0.1:{port}")
    yield health_pb2_grpc.HealthStub(channel)
    await channel.close()
    await server.stop(None)


async def test_records_the_duration_of_a_successful_call_with_its_method_and_ok(health_stub, reader):
    # Act
    await health_stub.Check(health_pb2.HealthCheckRequest(service="known"))

    # Assert
    [point] = duration_points(reader)
    assert point.count == 1
    assert dict(point.attributes) == {
        "rpc.system": "grpc",
        "rpc.service": "grpc.health.v1.Health",
        "rpc.method": "Check",
        "rpc.grpc.status_code": "OK",
    }


async def test_records_the_status_code_of_a_call_the_handler_aborted(health_stub, reader):
    # Act
    with pytest.raises(grpc.aio.AioRpcError) as error:
        await health_stub.Check(health_pb2.HealthCheckRequest(service="unknown"))

    # Assert
    assert error.value.code() == grpc.StatusCode.NOT_FOUND
    [point] = duration_points(reader)
    assert point.attributes["rpc.grpc.status_code"] == "NOT_FOUND"


@pytest.mark.parametrize("health_stub", [("grpc.health.v1.Health",)], indirect=True)
async def test_does_not_measure_an_excluded_service(health_stub, reader):
    # Act
    response = await health_stub.Check(health_pb2.HealthCheckRequest(service="known"))

    # Assert
    assert response.status == health_pb2.HealthCheckResponse.SERVING
    assert duration_points(reader) == []
