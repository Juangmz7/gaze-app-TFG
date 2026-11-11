import pytest
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import InMemoryMetricReader
from sqlalchemy import create_engine, text
from sqlalchemy.pool import NullPool, QueuePool

from observability.pool_metrics import register_pool_metrics

pytestmark = pytest.mark.unit


def collect(reader: InMemoryMetricReader) -> dict[str, dict[str, int]]:
    """Every data point by metric name, keyed by its connection state ("" without one)."""
    points: dict[str, dict[str, int]] = {}
    data = reader.get_metrics_data()
    for resource_metrics in data.resource_metrics if data else []:
        for scope_metrics in resource_metrics.scope_metrics:
            for metric in scope_metrics.metrics:
                for point in metric.data.data_points:
                    state = point.attributes.get("db.client.connection.state", "")
                    points.setdefault(metric.name, {})[state] = point.value
    return points


@pytest.fixture
def reader() -> InMemoryMetricReader:
    return InMemoryMetricReader()


def test_reports_idle_and_used_connections_and_the_pool_maximum(reader, tmp_path):
    # Arrange
    engine = create_engine(f"sqlite:///{tmp_path / 'pool.db'}", poolclass=QueuePool, pool_size=2, max_overflow=3)
    register_pool_metrics(engine, MeterProvider(metric_readers=[reader]))
    with engine.connect() as returned_to_the_pool:
        returned_to_the_pool.execute(text("select 1"))

    # Act
    with engine.connect() as in_use:
        in_use.execute(text("select 1"))
        while_in_use = collect(reader)
    after_release = collect(reader)

    # Assert
    assert while_in_use["db.client.connection.count"] == {"idle": 0, "used": 1}
    assert after_release["db.client.connection.count"] == {"idle": 1, "used": 0}
    assert after_release["db.client.connection.max"] == {"": 5}
    engine.dispose()


def test_reports_no_maximum_when_the_overflow_is_unlimited(reader, tmp_path):
    # Arrange
    engine = create_engine(f"sqlite:///{tmp_path / 'pool.db'}", poolclass=QueuePool, pool_size=2, max_overflow=-1)
    register_pool_metrics(engine, MeterProvider(metric_readers=[reader]))

    # Act
    points = collect(reader)

    # Assert
    assert "db.client.connection.max" not in points
    assert points["db.client.connection.count"] == {"idle": 0, "used": 0}
    engine.dispose()


def test_reports_nothing_for_a_pool_that_does_not_count_its_connections(reader, tmp_path):
    # Arrange
    engine = create_engine(f"sqlite:///{tmp_path / 'pool.db'}", poolclass=NullPool)
    register_pool_metrics(engine, MeterProvider(metric_readers=[reader]))

    # Act
    points = collect(reader)

    # Assert
    assert points == {}
    engine.dispose()
