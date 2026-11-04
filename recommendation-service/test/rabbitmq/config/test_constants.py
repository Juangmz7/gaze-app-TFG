import pytest

from rabbitmq.config.constants import (
    FEED_EXCHANGE,
    INCOMING_QUEUES,
    POST_EXCHANGE,
    POST_QUEUE,
    USER_EXCHANGE,
    USER_QUEUE,
)


pytestmark = pytest.mark.unit


def bound_routing_keys(exchange: str, queue: str) -> set[str]:
    return {
        routing_key
        for binding in INCOMING_QUEUES
        if binding["exchange"] == exchange and binding["queue"] == queue
        for routing_key in binding["routing_keys"]
    }


def test_user_queue_binds_the_follow_and_block_routing_keys_social_service_publishes():
    # Routing keys from social-service application.yaml (rabbitmq.rk.user.*)
    assert {
        "rk.user.follow.created",
        "rk.user.follow.deleted",
        "rk.user.block.created",
        "rk.user.block.deleted",
    } <= bound_routing_keys(USER_EXCHANGE, USER_QUEUE)


def test_feed_exhausted_is_bound_from_the_feed_exchange_only():
    assert "rk.post.feed.exhausted" in bound_routing_keys(FEED_EXCHANGE, POST_QUEUE)
    assert "rk.post.feed.exhausted" not in bound_routing_keys(POST_EXCHANGE, POST_QUEUE)
