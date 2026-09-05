from faststream.rabbit import RabbitQueue

from rabbitmq.config.constants import (
    POST_QUEUE,
    USER_QUEUE,
)
from rabbitmq.config.connection import broker
from rabbitmq.listener.post_event_listener import PostEventListener
from rabbitmq.listener.user_event_listener import UserEventListener


user_event_handler = UserEventListener()
post_event_handler = PostEventListener()


broker.subscriber(
    RabbitQueue(
        USER_QUEUE,
        declare=False,
    )
)(user_event_handler.handle_event)


broker.subscriber(
    RabbitQueue(
        POST_QUEUE,
        declare=False,
    )
)(post_event_handler.handle_event)
