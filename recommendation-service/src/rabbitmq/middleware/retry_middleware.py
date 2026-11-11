import json
import logging
from collections.abc import Awaitable, Callable
from typing import Any

from faststream import BaseMiddleware, StreamMessage
from faststream.exceptions import RejectMessage
from pydantic import ValidationError
from tenacity import (
    AsyncRetrying,
    retry_if_not_exception_type,
    stop_after_attempt,
    wait_exponential,
)

from rabbitmq.exception.exceptions import (
    RejectAndDontRequeueError,
)

logger = logging.getLogger(__name__)


class RabbitRetryMiddleware(BaseMiddleware):
    """Retries a failed message, then rejects it to its DLQ. Same policy as
    post-command-service, social-service and post-query-service: 2 retries,
    2 s initial delay, x2 multiplier, 100 s max delay.

    A body that is not JSON (JSONDecodeError, also raised for a body that is not
    UTF-8) or does not validate as its event (ValidationError) fails the same way
    on every attempt, so, like RejectAndDontRequeueError, it goes to the DLQ at once.
    FastStream decodes the body inside call_next, so these reach this middleware."""

    MAX_RETRIES = 2
    INITIAL_INTERVAL_SECONDS = 2
    MULTIPLIER = 2.0
    MAX_INTERVAL_SECONDS = 100

    NON_RETRYABLE_ERRORS = (RejectAndDontRequeueError, json.JSONDecodeError, ValidationError)

    async def consume_scope(
        self,
        call_next: Callable[[StreamMessage[Any]], Awaitable[Any]],
        msg: StreamMessage[Any],
    ) -> Any:

        try:
            async for attempt in AsyncRetrying(
                stop=stop_after_attempt(self.MAX_RETRIES + 1),
                wait=wait_exponential(
                    multiplier=self.INITIAL_INTERVAL_SECONDS,
                    exp_base=self.MULTIPLIER,
                    max=self.MAX_INTERVAL_SECONDS,
                ),
                retry=retry_if_not_exception_type(self.NON_RETRYABLE_ERRORS),
                reraise=True,
            ):
                with attempt:
                    return await call_next(msg)

        except ValidationError as exc:
            # Every error's location and type, not the raw input: it may be large or not even text
            logger.error(
                "Malformed event, rejecting message to DLQ: errors=%s",
                [(e["loc"], e["type"]) for e in exc.errors()],
            )
            raise RejectMessage() from exc

        except (RejectAndDontRequeueError, json.JSONDecodeError) as exc:
            logger.error("Non-retryable error, rejecting message to DLQ", exc_info=exc)
            raise RejectMessage() from exc

        except Exception as exc:
            # Retries exhausted
            logger.exception("Retries exhausted after %d attempts, rejecting message to DLQ",
                              self.MAX_RETRIES + 1)
            raise RejectMessage() from exc
