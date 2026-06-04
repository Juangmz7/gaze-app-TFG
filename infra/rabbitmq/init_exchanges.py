"""
RabbitMQ Exchange Initialization Script.

Connects to RabbitMQ, declares the required exchanges, and exits.
Includes retry logic to handle cases where RabbitMQ is still booting up.
"""

import os
import sys
import time
import logging

import pika
from pika.exceptions import AMQPConnectionError

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
)
logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

RABBITMQ_HOST = os.environ.get("RABBITMQ_HOST", "localhost")
RABBITMQ_PORT = int(os.environ.get("RABBITMQ_PORT", "5672"))
RABBITMQ_USER = os.environ.get("RABBITMQ_USER", "guest")
RABBITMQ_PASSWORD = os.environ.get("RABBITMQ_PASSWORD", "guest")

MAX_RETRIES = 10
RETRY_DELAY_SECONDS = 5

# Each entry: (name, type, durable)
EXCHANGES = [
    {"name": "keycloak-exchange", "type": "topic", "durable": True},
]


# Helpers

def build_connection_params() -> pika.ConnectionParameters:
    """Build pika connection parameters from environment variables."""
    credentials = pika.PlainCredentials(RABBITMQ_USER, RABBITMQ_PASSWORD)
    return pika.ConnectionParameters(
        host=RABBITMQ_HOST,
        port=RABBITMQ_PORT,
        credentials=credentials,
        connection_attempts=1,
        retry_delay=0,
    )


def connect_with_retries(params: pika.ConnectionParameters) -> pika.BlockingConnection:
    """Attempt to connect to RabbitMQ, retrying on failure."""
    for attempt in range(1, MAX_RETRIES + 1):
        try:
            logger.info(
                "Connecting to RabbitMQ at %s:%s (attempt %d/%d)…",
                params.host, params.port, attempt, MAX_RETRIES,
            )
            connection = pika.BlockingConnection(params)
            logger.info("Connected successfully.")
            return connection
        except AMQPConnectionError as exc:
            if attempt == MAX_RETRIES:
                logger.error("Max retries reached. Could not connect to RabbitMQ.")
                raise SystemExit(1) from exc
            logger.warning(
                "Connection failed: %s — retrying in %ds…", exc, RETRY_DELAY_SECONDS
            )
            time.sleep(RETRY_DELAY_SECONDS)

    # Should never be reached, but keeps the type checker happy.
    raise SystemExit(1)


def declare_exchanges(channel: pika.channel.Channel) -> None:
    """Declare every exchange listed in EXCHANGES."""
    for exchange in EXCHANGES:
        logger.info(
            "Declaring exchange '%s' (type=%s, durable=%s)…",
            exchange["name"], exchange["type"], exchange["durable"],
        )
        channel.exchange_declare(
            exchange=exchange["name"],
            exchange_type=exchange["type"],
            durable=exchange["durable"],
        )
        logger.info("Exchange '%s' declared.", exchange["name"])



# Main

def main() -> None:
    params = build_connection_params()
    connection = connect_with_retries(params)

    try:
        channel = connection.channel()
        declare_exchanges(channel)
        logger.info("All exchanges declared successfully.")
    finally:
        connection.close()
        logger.info("Connection closed. Exiting.")


if __name__ == "__main__":
    main()
