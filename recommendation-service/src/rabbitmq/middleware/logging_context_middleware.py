from collections.abc import Awaitable, Callable
from typing import Any

from faststream import BaseMiddleware, StreamMessage
from faststream.message import decode_message

from observability.log_context import (
    correlation_id_ctx,
    event_id_ctx,
)

## Extracts the correlation ID and event ID from the message body and sets them in the context variables for logging purposes.
class LoggingContextMiddleware(BaseMiddleware):

    async def consume_scope(
        self,
        call_next: Callable[[StreamMessage[Any]], Awaitable[Any]],
        msg: StreamMessage[Any],
    ) -> Any:

        event_id: str | None = None
        correlation_id: str | None = msg.correlation_id

        try:
            body = decode_message(msg)

            if isinstance(body, dict):
                raw_event_id = (
                    body.get("eventId")
                    or body.get("event_id")
                    or body.get("id")
                )

                raw_correlation_id = (
                    body.get("correlationId")
                    or body.get("correlation_id")
                )

                if raw_event_id is not None:
                    event_id = str(raw_event_id)

                if raw_correlation_id is not None:
                    correlation_id = str(raw_correlation_id)

        except (ValueError, TypeError):
            pass

        event_token = event_id_ctx.set(event_id)
        correlation_token = correlation_id_ctx.set(correlation_id)

        try:
            return await call_next(msg)

        finally:
            event_id_ctx.reset(event_token)
            correlation_id_ctx.reset(correlation_token)
