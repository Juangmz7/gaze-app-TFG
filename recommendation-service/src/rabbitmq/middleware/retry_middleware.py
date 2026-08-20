import logging
from collections.abc import Awaitable, Callable
from typing import Any

from faststream import BaseMiddleware, StreamMessage
from faststream.exceptions import RejectMessage
from tenacity import (
    AsyncRetrying,
    retry_if_not_exception_type,
    stop_after_attempt,
    wait_exponential,
)

from src.rabbitmq.exception.exceptions import (
    RejectAndDontRequeueError,
)

logger = logging.getLogger(__name__)


class RabbitRetryMiddleware(BaseMiddleware):

    MAX_RETRIES = 3
    INITIAL_INTERVAL_SECONDS = 2
    MULTIPLIER = 2.0
    MAX_INTERVAL_SECONDS = 100

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
                retry=retry_if_not_exception_type(
                    RejectAndDontRequeueError
                ),
                reraise=True,
            ):
                with attempt:
                    return await call_next(msg)

        except RejectAndDontRequeueError as exc:
            logger.error("Non-retryable error, rejecting message to DLQ", exc_info=exc)
            raise RejectMessage() from exc

        except Exception as exc:
            # Retries exhausted
            logger.exception("Retries exhausted after %d attempts, rejecting message to DLQ",
                              self.MAX_RETRIES)
            raise RejectMessage() from exc