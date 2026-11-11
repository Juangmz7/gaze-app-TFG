from collections.abc import Iterable

from opentelemetry import metrics
from opentelemetry.metrics import CallbackOptions, MeterProvider, Observation
from sqlalchemy.engine import Engine

METER_NAME = "recommendation-service.database"
STATE = "db.client.connection.state"


def register_pool_metrics(engine: Engine, meter_provider: MeterProvider | None = None) -> None:
    """Reports the engine's connection pool with the OpenTelemetry database client
    names, the same ones post-query-service uses for its Mongo pool:

    - db.client.connection.count, by db.client.connection.state (idle, used)
    - db.client.connection.max: pool size plus overflow

    The SQLAlchemy instrumentation would report them, but it does not support
    SQLAlchemy 2.1. The pool is read when the metrics are exported, so nothing runs
    on the request path. meter_provider defaults to the global one, which does
    nothing unless opentelemetry-instrument configured it."""
    meter = metrics.get_meter(METER_NAME, meter_provider=meter_provider)

    def connection_count(_: CallbackOptions) -> Iterable[Observation]:
        pool = engine.pool
        # Only QueuePool counts its connections; NullPool and StaticPool do not
        if not hasattr(pool, "checkedout"):
            return
        yield Observation(pool.checkedin(), {STATE: "idle"})
        yield Observation(pool.checkedout(), {STATE: "used"})

    def connection_max(_: CallbackOptions) -> Iterable[Observation]:
        pool = engine.pool
        if not hasattr(pool, "size"):
            return
        # -1 means the overflow has no limit: there is no maximum to report then
        max_overflow = getattr(pool, "_max_overflow", 0)
        if max_overflow < 0:
            return
        yield Observation(pool.size() + max_overflow)

    meter.create_observable_up_down_counter(
        "db.client.connection.count",
        callbacks=[connection_count],
        unit="{connection}",
        description="Connections of the pool, by state.",
    )
    meter.create_observable_up_down_counter(
        "db.client.connection.max",
        callbacks=[connection_max],
        unit="{connection}",
        description="Connections the pool may open: its size plus its overflow.",
    )
