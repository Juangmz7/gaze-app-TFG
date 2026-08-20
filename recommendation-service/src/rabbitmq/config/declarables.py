from faststream.rabbit import (
    ExchangeType,
    RabbitBroker,
    RabbitExchange,
    RabbitQueue,
)

from rabbitmq.config.constants import (
    INCOMING_QUEUES,
    OUTGOING_EXCHANGES,
)


async def configure_rabbitmq_declarables(
    broker: RabbitBroker,
) -> None:
    """
    Declares every exchange, queue, binding and dead-letter topology
    required by this service.
    """

    declared_exchanges = {}

    async def declare_exchange(
        exchange_name: str,
        exchange_type: ExchangeType,
    ):
        if exchange_name in declared_exchanges:
            return declared_exchanges[exchange_name]

        exchange = await broker.declare_exchange(
            RabbitExchange(
                name=exchange_name,
                type=exchange_type,
                durable=True,
            )
        )

        declared_exchanges[exchange_name] = exchange
        return exchange

    # Incoming exchanges, queues and DLQs
    for binding in INCOMING_QUEUES:
        exchange_name = binding["exchange"]
        queue_name = binding["queue"]
        routing_keys = binding["routing_keys"]

        dlx_name = binding["dead_letter_exchange"]
        dlq_name = binding["dead_letter_queue"]
        dlq_routing_key = binding["dead_letter_routing_key"]

        # Main exchange: TOPIC
        exchange = await declare_exchange(
            exchange_name,
            ExchangeType.TOPIC,
        )

        # Dead Letter Exchange: DIRECT
        dlx = await declare_exchange(
            dlx_name,
            ExchangeType.DIRECT,
        )

        # Main queue
        queue = await broker.declare_queue(
            RabbitQueue(
                name=queue_name,
                durable=True,
                arguments={
                    "x-dead-letter-exchange": dlx_name,
                    "x-dead-letter-routing-key": dlq_routing_key,
                },
            )
        )

        # Bind all routing keys to the same queue
        for routing_key in routing_keys:
            await queue.bind(
                exchange=exchange,
                routing_key=routing_key,
            )

        # Dead Letter Queue
        dlq = await broker.declare_queue(
            RabbitQueue(
                name=dlq_name,
                durable=True,
            )
        )

        # DLX -> DLQ
        await dlq.bind(
            exchange=dlx,
            routing_key=dlq_routing_key,
        )

    # Outgoing exchanges
    for exchange_name in OUTGOING_EXCHANGES:
        await declare_exchange(
            exchange_name,
            ExchangeType.TOPIC,
        )
