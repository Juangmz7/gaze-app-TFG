import asyncio
from unittest.mock import Mock
from uuid import uuid4

import aio_pika
import pytest
from faststream.rabbit import Channel, RabbitBroker, RabbitQueue

from _support.feed_service import feed_request_body, get_message
from pipeline.worker.pipeline_worker_pool import PipelineWorkerPool
from post.usecase.exhaust_post_feed_usecase import ExhaustPostFeedUsecase
from rabbitmq.config.subscribers_decoder import raw_body_decoder
from rabbitmq.handler.post.post_feed_exhausted_event_handler import PostFeedExhaustedEventHandler
from rabbitmq.listener.feed_event_listener import FeedEventListener
from rabbitmq.middleware.retry_middleware import RabbitRetryMiddleware

pytestmark = pytest.mark.integration


async def test_pipeline_failure_uses_the_retry_middleware_then_the_dlq(rabbitmq_url, monkeypatch):
    # Arrange: a queue of this test with its DLQ, the real listener and a failing pipeline
    monkeypatch.setattr(RabbitRetryMiddleware, "INITIAL_INTERVAL_SECONDS", 0.01)
    suffix = uuid4().hex
    queue_name, dlq_name, exchange_name = f"q.retry.{suffix}", f"q.retry.{suffix}-dlq", f"x.retry.{suffix}"

    connection = await aio_pika.connect_robust(rabbitmq_url)
    channel = await connection.channel()
    dlx = await channel.declare_exchange(f"{exchange_name}.dlx", aio_pika.ExchangeType.DIRECT, durable=True)
    dlq = await channel.declare_queue(dlq_name, durable=True)
    await dlq.bind(dlx, routing_key="dead")
    exchange = await channel.declare_exchange(exchange_name, aio_pika.ExchangeType.TOPIC, durable=True)
    queue = await channel.declare_queue(queue_name, durable=True, arguments={
        "x-dead-letter-exchange": f"{exchange_name}.dlx",
        "x-dead-letter-routing-key": "dead",
    })
    await queue.bind(exchange, routing_key="rk.post.feed.exhausted")

    recommended_feed_usecase = Mock()
    recommended_feed_usecase.execute.side_effect = RuntimeError("model unavailable")
    processed_events = Mock()
    processed_events.isAlreadyProcessed.return_value = False
    listener = FeedEventListener(
        PostFeedExhaustedEventHandler(
            ExhaustPostFeedUsecase(recommended_feed_usecase, processed_events),
            PipelineWorkerPool(1),
        )
    )
    broker = RabbitBroker(rabbitmq_url, middlewares=[RabbitRetryMiddleware])
    broker.subscriber(
        RabbitQueue(queue_name, declare=False), channel=Channel(prefetch_count=1), decoder=raw_body_decoder,
    )(listener.handle_event)
    body = feed_request_body()

    # Act
    async with broker:
        await broker.start()
        await exchange.publish(
            aio_pika.Message(body, content_type="application/json"), routing_key="rk.post.feed.exhausted"
        )
        message = await asyncio.wait_for(get_message(channel, dlq_name), timeout=30)

    # Assert: the first attempt plus MAX_RETRIES, then dead-lettered
    assert message.body == body
    assert recommended_feed_usecase.execute.call_count == RabbitRetryMiddleware.MAX_RETRIES + 1
    await connection.close()
