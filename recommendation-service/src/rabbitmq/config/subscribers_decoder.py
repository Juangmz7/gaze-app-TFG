from typing import Any


async def raw_body_decoder(message: Any) -> bytes:
    """Hands the raw body to the handler. FeedEventListener validates it itself,
    so that a malformed payload skips the retry middleware."""
    return message.body
