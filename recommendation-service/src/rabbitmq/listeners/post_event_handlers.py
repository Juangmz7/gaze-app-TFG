from faststream.rabbit import RabbitMessage, RabbitQueue

from src.block.rabbitmq.config.constants import (
    POST_QUEUE,
    PostRoutingKey,
)
from src.block.rabbitmq.config.rabbitmq import broker


@broker.subscriber(
    RabbitQueue(
        POST_QUEUE,
        declare=False,
    )
)
async def handle_post_event(
    body: dict,
    message: RabbitMessage,
) -> None:
    routing_key = message.raw_message.routing_key

    match routing_key:
        case PostRoutingKey.SHARE_DELETED:
            await handle_post_share_deleted(body)

        case PostRoutingKey.SHARE_CREATED:
            await handle_post_share_created(body)

        case PostRoutingKey.COLLAB_REQUEST_CREATED:
            await handle_post_collab_request_created(body)

        case PostRoutingKey.COLLAB_REQUEST_DELETED:
            await handle_post_collab_request_deleted(body)

        case PostRoutingKey.COMMENT_LIKE_DELETED:
            await handle_post_comment_like_deleted(body)

        case PostRoutingKey.COMMENT_LIKE_CREATED:
            await handle_post_comment_like_created(body)

        case PostRoutingKey.COMMENT_DELETED:
            await handle_post_comment_deleted(body)

        case PostRoutingKey.COMMENT_CREATED:
            await handle_post_comment_created(body)

        case PostRoutingKey.VIEWED:
            await handle_post_viewed(body)

        case PostRoutingKey.LIKE_DELETED:
            await handle_post_like_deleted(body)

        case PostRoutingKey.LIKE_CREATED:
            await handle_post_like_created(body)

        case PostRoutingKey.BANNED:
            await handle_post_banned(body)

        case PostRoutingKey.DELETED:
            await handle_post_deleted(body)

        case PostRoutingKey.UPDATED:
            await handle_post_updated(body)

        case PostRoutingKey.CREATED:
            await handle_post_created(body)

        case PostRoutingKey.FEED_EXHAUSTED:
            await handle_post_feed_exhausted(body)

        case _:
            raise ValueError(
                f"Unsupported post routing key: {routing_key}"
            )


async def handle_post_share_deleted(body: dict) -> None:
    pass


async def handle_post_share_created(body: dict) -> None:
    pass


async def handle_post_collab_request_created(body: dict) -> None:
    pass


async def handle_post_collab_request_deleted(body: dict) -> None:
    pass


async def handle_post_comment_like_deleted(body: dict) -> None:
    pass


async def handle_post_comment_like_created(body: dict) -> None:
    pass


async def handle_post_comment_deleted(body: dict) -> None:
    pass


async def handle_post_comment_created(body: dict) -> None:
    pass


async def handle_post_viewed(body: dict) -> None:
    pass


async def handle_post_like_deleted(body: dict) -> None:
    pass


async def handle_post_like_created(body: dict) -> None:
    pass


async def handle_post_banned(body: dict) -> None:
    pass


async def handle_post_deleted(body: dict) -> None:
    pass


async def handle_post_updated(body: dict) -> None:
    pass


async def handle_post_created(body: dict) -> None:
    pass


async def handle_post_feed_exhausted(body: dict) -> None:
    pass