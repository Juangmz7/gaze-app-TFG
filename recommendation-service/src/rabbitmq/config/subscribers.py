from faststream.rabbit import RabbitQueue

from rabbitmq.config.constants import (
    POST_QUEUE,
    USER_QUEUE,
)
from rabbitmq.config.connection import broker
from shared.config.containers import container


user_event_listener = container.user_event_listener
post_event_listener = container.post_event_listener


broker.subscriber(
    RabbitQueue(
        USER_QUEUE,
        declare=False,
    )
)(user_event_listener.handle_event)


broker.subscriber(
    RabbitQueue(
        POST_QUEUE,
        declare=False,
    )
)(post_event_listener.handle_event)
