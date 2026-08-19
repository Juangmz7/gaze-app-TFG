from faststream.rabbit import RabbitMessage, RabbitQueue

from src.block.rabbitmq.config.constants import (
    USER_QUEUE,
    UserRoutingKey,
)
from src.block.rabbitmq.config.rabbitmq import broker


@broker.subscriber(
    RabbitQueue(
        USER_QUEUE,
        declare=False,
    )
)
async def handle_user_event(
    body: dict,
    message: RabbitMessage,
) -> None:
    routing_key = message.raw_message.routing_key

    match routing_key:
        case UserRoutingKey.FOLLOW_DELETED:
            await handle_user_follow_deleted(body)

        case UserRoutingKey.FOLLOW_CREATED:
            await handle_user_follow_created(body)

        case UserRoutingKey.BLOCK_DELETED:
            await handle_user_block_deleted(body)

        case UserRoutingKey.DELETED:
            await handle_user_deleted(body)

        case UserRoutingKey.BLOCK_CREATED:
            await handle_user_block_created(body)

        case UserRoutingKey.REGISTERED:
            await handle_user_registered(body)

        case UserRoutingKey.UPDATED:
            await handle_user_updated(body)

        case _:
            raise ValueError(
                f"Unsupported user routing key: {routing_key}"
            )


async def handle_user_follow_deleted(body: dict) -> None:
    pass


async def handle_user_follow_created(body: dict) -> None:
    pass


async def handle_user_block_deleted(body: dict) -> None:
    pass


async def handle_user_deleted(body: dict) -> None:
    pass


async def handle_user_block_created(body: dict) -> None:
    pass


async def handle_user_registered(body: dict) -> None:
    pass


async def handle_user_updated(body: dict) -> None:
    pass