from typing import Any

from faststream.opentelemetry.consts import OTEL_SCHEMA
from opentelemetry import metrics
from opentelemetry.metrics import Histogram, Meter

# The meter FastStream creates itself (faststream.opentelemetry.middleware._get_meter), so
# its series keep their instrumentation scope
FASTSTREAM_METER_NAME = "faststream.opentelemetry.middleware"
# Seconds, the same as post-query-service's messaging.process.duration: a feed request
# runs the pipeline (seconds) and a retried message waits its backoff (2 s, then 4 s)
MESSAGING_DURATION_BUCKETS_S = (0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60, 120)


## The meter FastStream's RabbitTelemetryMiddleware records through. FastStream creates
## messaging.process.duration and messaging.publish.duration in seconds without bucket
## boundaries, so they got the SDK's defaults, which are meant for milliseconds (0, 5,
## 10, 25...): every message under 5 s fell in one bucket and a p95 read as ~19 s. This
## gives its histograms in seconds these boundaries; anything else passes through.
class SecondsHistogramMeter:
    def __init__(self, meter: Meter | None = None) -> None:
        self._meter = meter if meter is not None else metrics.get_meter(FASTSTREAM_METER_NAME, schema_url=OTEL_SCHEMA)

    def create_histogram(self, name: str, unit: str = "", description: str = "", **kwargs: Any) -> Histogram:
        if unit == "s":
            kwargs.setdefault("explicit_bucket_boundaries_advisory", MESSAGING_DURATION_BUCKETS_S)
        return self._meter.create_histogram(name, unit=unit, description=description, **kwargs)

    def __getattr__(self, name: str) -> Any:
        return getattr(self._meter, name)
