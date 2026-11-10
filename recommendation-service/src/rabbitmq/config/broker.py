import os

from faststream.rabbit import RabbitBroker
from faststream.rabbit.opentelemetry import RabbitTelemetryMiddleware

from rabbitmq.middleware.logging_context_middleware import LoggingContextMiddleware
from rabbitmq.middleware.retry_middleware import RabbitRetryMiddleware

RABBITMQ_URL = os.getenv(
    "RABBITMQ_URL",
    "amqp://guest:guest@localhost:5672/",
)

# Bounds each connection attempt (default: no limit). Keeps relay cycles short
# and well under the outbox lock timeout while the broker is unreachable.
RABBITMQ_CONNECTION_TIMEOUT_S = 5.0

broker = RabbitBroker(
    RABBITMQ_URL,
    timeout=RABBITMQ_CONNECTION_TIMEOUT_S,
    middlewares=[
        LoggingContextMiddleware,
        RabbitRetryMiddleware,
        RabbitTelemetryMiddleware()
    ],
)
