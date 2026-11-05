"""What the real entrypoint does with these is covered through run_real_service in
test/feed/test_feed_service_integration.py. These cover what it does not reach: the
handlers opentelemetry-instrument may have attached before the application starts."""
import logging
import sys

import pytest

from observability.logging_config import configure_logging

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
