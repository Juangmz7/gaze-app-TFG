import pytest

from rabbitmq.config.constants import (
    FEED_DLQ,
    FEED_DLX,
    FEED_EXCHANGE,
    FEED_QUEUE,
    INCOMING_QUEUES,
    OBSOLETE_BINDINGS,
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


def test_feed_exhausted_has_its_own_queue_on_the_feed_exchange():
    assert FEED_QUEUE == "q.recommendation-service.feed"
    assert FEED_EXCHANGE == "x.feed.events"
    assert bound_routing_keys(FEED_EXCHANGE, FEED_QUEUE) == {"rk.post.feed.exhausted"}
    assert "rk.post.feed.exhausted" not in bound_routing_keys(FEED_EXCHANGE, POST_QUEUE)
    assert "rk.post.feed.exhausted" not in bound_routing_keys(POST_EXCHANGE, POST_QUEUE)


def test_feed_queue_has_its_own_dead_letter_topology():
    [binding] = [b for b in INCOMING_QUEUES if b["queue"] == FEED_QUEUE]
    assert binding["dead_letter_exchange"] == FEED_DLX
    assert binding["dead_letter_queue"] == FEED_DLQ == "q.recommendation-service.feed-dlq"


def test_old_feed_binding_on_the_post_queue_is_removed_from_existing_brokers():
    assert OBSOLETE_BINDINGS == [
        {"exchange": FEED_EXCHANGE, "queue": POST_QUEUE, "routing_keys": ["rk.post.feed.exhausted"]}
    ]
