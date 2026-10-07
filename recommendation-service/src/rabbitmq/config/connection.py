import os

from faststream import FastStream
from faststream.rabbit import RabbitBroker
from faststream.rabbit.opentelemetry import RabbitTelemetryMiddleware

from rabbitmq.middleware.retry_middleware import RabbitRetryMiddleware
from rabbitmq.config.declarables import (
    configure_rabbitmq_declarables,
)
from rabbitmq.worker.outbox_relay import OutboxRelayWorker
from shared.config.database import SQLAlchemySessionProvider

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

app = FastStream(broker)

relay_worker = OutboxRelayWorker(SQLAlchemySessionProvider(), broker)


@app.after_startup
async def configure_rabbitmq() -> None:
    await configure_rabbitmq_declarables(broker)
    relay_worker.start()


@app.on_shutdown
async def stop_outbox_relay() -> None:
    await relay_worker.stop()
