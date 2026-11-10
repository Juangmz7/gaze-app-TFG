from faststream.rabbit import Channel, RabbitQueue

from rabbitmq.config.broker import broker
from rabbitmq.config.constants import (
    FEED_QUEUE,
    POST_QUEUE,
    USER_QUEUE,
)
from rabbitmq.config.subscribers_decoder import raw_body_decoder
from shared.config.containers import container
from shared.config.env import positive_int_env

# Feed requests a replica takes at once. Below PIPELINE_WORKER_POOL_SIZE by
# default, so prefetches leave a worker free for the synchronous gRPC path.
FEED_CONSUMER_PREFETCH = positive_int_env("FEED_CONSUMER_PREFETCH", 1)


broker.subscriber(
    RabbitQueue(
        USER_QUEUE,
        declare=False,
    )
)(container.user_event_listener.handle_event)


broker.subscriber(
    RabbitQueue(
        POST_QUEUE,
        declare=False,
    )
)(container.post_event_listener.handle_event)


broker.subscriber(
    RabbitQueue(
        FEED_QUEUE,
        declare=False,
    ),
    channel=Channel(prefetch_count=FEED_CONSUMER_PREFETCH),
    decoder=raw_body_decoder,
)(container.feed_event_listener.handle_event)
