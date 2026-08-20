import logging

from src.observability.log_context import (
    correlation_id_ctx,
    event_id_ctx,
)


_installed = False

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