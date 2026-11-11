"""What the real entrypoint does with these is covered through run_real_service in
test/feed/test_feed_service_integration.py. These cover what it does not reach: the
handlers opentelemetry-instrument may have attached before the application starts."""
import io
import logging
import sys

import pytest

from observability.log_context import correlation_id_ctx, event_id_ctx
from observability.logging_config import (
    LOG_FORMAT,
    LOG_FORMAT_DEFAULTS,
    configure_log_context,
    configure_logging,
)

pytestmark = pytest.mark.unit


@pytest.fixture(autouse=True)
def isolated_root_logger(monkeypatch):
    monkeypatch.delenv("LOG_LEVEL", raising=False)
    root_logger = logging.getLogger()
    handlers, level = root_logger.handlers[:], root_logger.level
    pipeline_level = logging.getLogger("pipeline").level
    root_logger.handlers[:] = []
    yield root_logger
    root_logger.handlers[:] = handlers
    root_logger.setLevel(level)
    logging.getLogger("pipeline").setLevel(pipeline_level)


def console_handlers(root_logger):
    # By stream: pytest's own capture handlers are StreamHandlers too
    return [
        handler for handler in root_logger.handlers
        if isinstance(handler, logging.StreamHandler) and handler.stream in (sys.stdout, sys.stderr)
    ]


def test_should_keep_a_handler_already_attached_and_also_print_to_stdout(isolated_root_logger):
    opentelemetry_like_handler = logging.NullHandler()
    isolated_root_logger.addHandler(opentelemetry_like_handler)

    configure_logging()

    assert opentelemetry_like_handler in isolated_root_logger.handlers
    assert [handler.stream for handler in console_handlers(isolated_root_logger)] == [sys.stdout]


def test_should_not_add_a_second_console_handler_when_one_exists(isolated_root_logger):
    existing_console_handler = logging.StreamHandler(sys.stderr)
    isolated_root_logger.addHandler(existing_console_handler)

    configure_logging()

    assert console_handlers(isolated_root_logger) == [existing_console_handler]


def test_should_not_add_a_second_console_handler_when_called_twice(isolated_root_logger):
    configure_logging()
    configure_logging()

    assert len(console_handlers(isolated_root_logger)) == 1


def test_should_let_application_info_through_and_keep_libraries_at_warning():
    configure_logging()

    assert logging.getLogger("pipeline.usecase.any_module").isEnabledFor(logging.INFO)
    assert not logging.getLogger("sqlalchemy.engine.Engine").isEnabledFor(logging.INFO)
    assert logging.getLogger("sqlalchemy.engine.Engine").isEnabledFor(logging.WARNING)


def test_log_context_injects_ids_into_records():
    # Arrange
    configure_log_context()
    correlation_token = correlation_id_ctx.set("corr-1")
    event_token = event_id_ctx.set("event-1")

    # Act
    try:
        record = logging.getLogRecordFactory()(
            "test", logging.INFO, __file__, 1, "msg", None, None
        )
    finally:
        event_id_ctx.reset(event_token)
        correlation_id_ctx.reset(correlation_token)

    # Assert
    assert record.correlationId == "corr-1"
    assert record.eventId == "event-1"


def test_log_context_leaves_records_untouched_without_ids():
    # Arrange
    configure_log_context()

    # Act
    record = logging.getLogRecordFactory()(
        "test", logging.INFO, __file__, 1, "msg", None, None
    )

    # Assert
    assert not hasattr(record, "correlationId")
    assert not hasattr(record, "eventId")


def test_log_context_is_installed_once():
    # Arrange
    configure_log_context()
    factory = logging.getLogRecordFactory()

    # Act
    configure_log_context()

    # Assert
    assert logging.getLogRecordFactory() is factory


def test_log_format_renders_without_otel_or_context_attributes():
    # Arrange
    stream = io.StringIO()
    handler = logging.StreamHandler(stream)
    handler.setFormatter(logging.Formatter(LOG_FORMAT, defaults=LOG_FORMAT_DEFAULTS))
    record = logging.LogRecord("svc", logging.INFO, __file__, 1, "hello", None, None)

    # Act
    handler.handle(record)

    # Assert
    output = stream.getvalue()
    assert "trace_id=0 span_id=0 correlation_id=- event_id=-" in output
    assert "INFO [svc] [trace_id=" in output
    assert output.rstrip().endswith("] hello")


def test_log_format_renders_trace_and_context_attributes():
    # Arrange
    formatter = logging.Formatter(LOG_FORMAT, defaults=LOG_FORMAT_DEFAULTS)
    record = logging.LogRecord("svc", logging.INFO, __file__, 1, "hello", None, None)
    record.otelTraceID = "abc"
    record.otelSpanID = "def"
    record.correlationId = "corr-1"
    record.eventId = "event-1"

    # Act
    output = formatter.format(record)

    # Assert
    assert "trace_id=abc span_id=def correlation_id=corr-1 event_id=event-1" in output
