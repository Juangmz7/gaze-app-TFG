from faststream.rabbit import RabbitQueue

from src.rabbitmq.config.constants import (
    POST_QUEUE,
    USER_QUEUE,
)
from src.rabbitmq.config.connection import broker
from src.rabbitmq.listener.post_event_handlers import PostEventHandler
from src.rabbitmq.listener.user_event_listeners import UserEventHandler


user_event_handler = UserEventHandler()
post_event_handler = PostEventHandler()


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