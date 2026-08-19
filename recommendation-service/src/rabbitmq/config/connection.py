import os

from faststream import FastStream
from faststream.rabbit import RabbitBroker

from src.rabbitmq.config.declarables import (
    configure_rabbitmq_declarables,
)

RABBITMQ_URL = os.getenv(
    "RABBITMQ_URL",
    "amqp://guest:guest@localhost:5672/",
)

broker = RabbitBroker(RABBITMQ_URL)

app = FastStream(broker)

@app.after_startup
async def configure_rabbitmq() -> None:
    await configure_rabbitmq_declarables(broker)