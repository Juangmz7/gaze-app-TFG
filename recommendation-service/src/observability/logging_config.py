import logging
import sys
from pathlib import Path

from observability.log_context import (
    correlation_id_ctx,
    event_id_ctx,
)
from shared.config.env import log_level_env


LOG_LEVEL_ENV = "LOG_LEVEL"
DEFAULT_LOG_LEVEL = logging.INFO
# Libraries stay here whatever LOG_LEVEL says, so that LOG_LEVEL=DEBUG shows this
# service's lines and not every AMQP frame
THIRD_PARTY_LOG_LEVEL = logging.WARNING
# otelTraceID/otelSpanID come from the OpenTelemetry LoggingInstrumentor that
# opentelemetry-instrument runs, correlationId/eventId from configure_log_context.
# The defaults keep the format valid when they are missing: tests, a run without
# opentelemetry-instrument, a log line outside a message.
LOG_FORMAT = (
    "%(asctime)s %(levelname)s [%(name)s] "
    "[trace_id=%(otelTraceID)s span_id=%(otelSpanID)s "
    "correlation_id=%(correlationId)s event_id=%(eventId)s] %(message)s"
)
LOG_FORMAT_DEFAULTS = {
    "otelTraceID": "0",
    "otelSpanID": "0",
    "correlationId": "-",
    "eventId": "-",
}
SOURCE_ROOT = Path(__file__).resolve().parents[1]

_installed = False


## Sets the levels and the console output, without depending on any OTEL_* variable.
## Handlers that are already on the root logger, like the OpenTelemetry one, stay and
## receive the same records. FastStream and uvicorn loggers do not propagate to the
## root logger and keep their own handlers, so their lines are not printed twice.
def configure_logging() -> None:
    level = log_level_env(LOG_LEVEL_ENV, DEFAULT_LOG_LEVEL)

    root_logger = logging.getLogger()
    root_logger.setLevel(THIRD_PARTY_LOG_LEVEL)
    if not _has_console_handler(root_logger):
        handler = logging.StreamHandler(sys.stdout)
        handler.setFormatter(logging.Formatter(LOG_FORMAT, defaults=LOG_FORMAT_DEFAULTS))
        root_logger.addHandler(handler)

    for package in _application_packages():
        logging.getLogger(package).setLevel(level)


## opentelemetry-instrument adds a stderr handler itself with OTEL_PYTHON_LOG_CORRELATION
def _has_console_handler(logger: logging.Logger) -> bool:
    return any(
        isinstance(handler, logging.StreamHandler) and handler.stream in (sys.stdout, sys.stderr)
        for handler in logger.handlers
    )


## Every module logs through getLogger(__name__), so a package's logger covers its
## modules. Read from disk so that a new package cannot be left out and go silent.
def _application_packages() -> list[str]:
    return [
        path.name
        for path in SOURCE_ROOT.iterdir()
        if path.is_dir() and path.name.isidentifier() and not path.name.startswith("_")
    ]


## Extracts the correlation ID and event ID from the context variables and adds them to the log record
def configure_log_context() -> None:
    global _installed

    if _installed:
        return

    old_factory = logging.getLogRecordFactory()

    def record_factory(*args, **kwargs) -> logging.LogRecord:
        record = old_factory(*args, **kwargs)

        correlation_id = correlation_id_ctx.get()
        event_id = event_id_ctx.get()

        if correlation_id is not None:
            record.correlationId = correlation_id

        if event_id is not None:
            record.eventId = event_id

        return record

    logging.setLogRecordFactory(record_factory)

    _installed = True

