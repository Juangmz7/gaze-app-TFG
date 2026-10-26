import os

from faststream.asgi import AsgiFastStream, AsgiResponse, get, make_ping_asgi
from faststream.rabbit import RabbitBroker
from faststream.rabbit.opentelemetry import RabbitTelemetryMiddleware

from rabbitmq.middleware.retry_middleware import RabbitRetryMiddleware
from rabbitmq.config.declarables import (
    configure_rabbitmq_declarables,
)

RABBITMQ_URL = os.getenv(
    "RABBITMQ_URL",
    "amqp://guest:guest@localhost:5672/",
)

broker = RabbitBroker(
    RABBITMQ_URL,
    middlewares=[
        RabbitRetryMiddleware,
        RabbitTelemetryMiddleware()
    ],
)


@get
async def liveness(scope) -> AsgiResponse:
    return AsgiResponse(b"", status_code=204)


# ASGI app only to serve the Kubernetes probes: liveness reports the process
# is up, readiness pings the broker (204 when connected, 500 otherwise).
app = AsgiFastStream(
    broker,
    asgi_routes=[
        ("/healthz/live", liveness),
        ("/healthz/ready", make_ping_asgi(broker, timeout=5.0)),
    ],
)

@app.after_startup
async def configure_rabbitmq() -> None:
    await configure_rabbitmq_declarables(broker)
