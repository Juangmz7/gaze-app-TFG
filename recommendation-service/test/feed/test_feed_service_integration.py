"""The real service, started through its real entrypoint, against a real broker
and database: only the embedding model is replaced."""
import asyncio
import json
import subprocess
import sys
import time
from datetime import datetime
from types import SimpleNamespace
from uuid import uuid4

import aio_pika
import grpc
import pytest
from grpc_health.v1 import health_pb2
from sqlalchemy import text

from _support.feed_service import (
    T0,
    FEED_DLQ,
    FEED_QUEUE,
    POST_DLQ,
    POST_QUEUE,
    SERVICE_ROOT,
    Amqp,
    feed_request_body,
    get_message,
    grpc_channel_stub,
    health_status,
    http_status,
    run_service,
    seed_post,
    seed_user_with_signal,
    service_env,
)
from block.entity.block_entity import BlockRecord
from follow.entity.follow_entity import FollowRecord
from pipeline.entity.post.post_tag_features_entity import PostTagFeaturesRecord
from pipeline.entity.user.user_creator_features_entity import UserCreatorFeaturesRecord
from pipeline.usecase.semantic_post_retrieval_use_case import POST_LIMIT as SEMANTIC_POST_LIMIT
from post.entity.user_post_interactions_entity import UserPostInteractionsRecord
from recommendation.v1 import recommendation_pb2

pytestmark = pytest.mark.integration


@pytest.fixture(scope="module")
def service(postgres_url, rabbitmq_url):
    # The binding older versions declared: startup has to remove it from a broker that has it
    asyncio.run(_bind_feed_requests_to_the_post_queue_like_older_versions(rabbitmq_url))
    with run_service(postgres_url, rabbitmq_url) as running:
        yield running


async def _bind_feed_requests_to_the_post_queue_like_older_versions(rabbitmq_url):
    connection = await aio_pika.connect_robust(rabbitmq_url)
    channel = await connection.channel()
    await channel.declare_exchange("x.recommendation-service.post.dlx", aio_pika.ExchangeType.DIRECT, durable=True)
    exchange = await channel.declare_exchange("x.feed.events", aio_pika.ExchangeType.TOPIC, durable=True)
    queue = await channel.declare_queue(
        POST_QUEUE,
        durable=True,
        arguments={
            "x-dead-letter-exchange": "x.recommendation-service.post.dlx",
            "x-dead-letter-routing-key": "rk.recommendation-service.post.dlq",
        },
    )
    await queue.bind(exchange, routing_key="rk.post.feed.exhausted")
    await connection.close()


@pytest.fixture()
async def amqp(service, rabbitmq_url):
    client = await Amqp.connect(rabbitmq_url)
    await client.purge(FEED_QUEUE, FEED_DLQ, POST_QUEUE, POST_DLQ)
    yield client
    await client.close()


@pytest.fixture()
async def stub(service):
    channel, recommendation_stub = await grpc_channel_stub(service)
    yield recommendation_stub
    await channel.close()


def seed_ranked_posts(db_session_factory, count):
    """Posts whose final order is known: everything else being equal, the reranker
    orders by watch time, so the lower the index the better the post."""
    with db_session_factory() as session:
        post_ids = [seed_post(session, watch_time_percent=(count - i) / 100) for i in range(count)]
        session.commit()
    return post_ids


def seed_user(db_session_factory):
    with db_session_factory() as session:
        user_id = seed_user_with_signal(session)
        session.commit()
    return user_id


def outbox_rows(db_session_factory):
    with db_session_factory() as session:
        return session.execute(text("SELECT * FROM outbox_events")).mappings().all()


async def wait_until(condition, timeout=20.0):
    async with asyncio.timeout(timeout):
        while not await asyncio.to_thread(condition):
            await asyncio.sleep(0.05)


def test_importing_the_real_application_registers_every_subscriber():
    # Its own process: the import has to work without anything else having run first
    result = subprocess.run(
        [sys.executable, str(SERVICE_ROOT / "test" / "_support" / "run_real_service.py"), "--print-subscribers"],
        cwd=SERVICE_ROOT,
        env=service_env(DATABASE_URL="postgresql+psycopg://unused:unused@localhost:1/unused"),
        capture_output=True,
        text=True,
        timeout=180,
    )

    assert result.returncode == 0, result.stderr
    assert sorted(json.loads(result.stdout.splitlines()[-1])) == [
        "q.recommendation-service.feed",
        "q.recommendation-service.post",
        "q.recommendation-service.user",
    ]


async def test_grpc_health_service_reports_serving(service):
    assert await health_status(service) == health_pb2.HealthCheckResponse.SERVING
    assert (
        await health_status(service, "recommendation.v1.RecommendationService")
        == health_pb2.HealthCheckResponse.SERVING
    )


async def test_probes_answer(service):
    assert await asyncio.to_thread(http_status, service, "/healthz/live") == 204
    assert await asyncio.to_thread(http_status, service, "/healthz/ready") == 204


async def test_grpc_client_receives_ordered_ids_that_exclude_the_excluded_ids(stub, db_session_factory):
    # Arrange: 3 excluded + exactly 30 left, so 30 can only come back if the
    # exclusions are removed before counting the target
    user_id = seed_user(db_session_factory)
    post_ids = seed_ranked_posts(db_session_factory, 33)
    excluded, eligible = post_ids[:3], post_ids[3:]

    # Act
    response = await stub.GetRecommendedFeed(
        recommendation_pb2.GetRecommendedFeedRequest(
            user_id=str(user_id),
            excluded_post_ids=[str(post_id) for post_id in excluded],
            reason=recommendation_pb2.FEED_EMPTY,
        ),
        timeout=30,
    )

    # Assert
    assert list(response.post_ids) == [str(post_id) for post_id in eligible]


async def test_grpc_returns_at_most_30_ids(stub, db_session_factory):
    user_id = seed_user(db_session_factory)
    seed_ranked_posts(db_session_factory, 45)

    response = await stub.GetRecommendedFeed(
        recommendation_pb2.GetRecommendedFeedRequest(user_id=str(user_id)), timeout=30
    )

    assert len(response.post_ids) == 30
    assert len(set(response.post_ids)) == 30


def seed_user_with_history(db_session_factory):
    """A user with a similar user, whose favourite creators are how collaborative
    retrieval finds posts. The post sets are only reachable through it: their creator
    is followed, which explorative retrieval skips; they have enough views to skip
    cold start; and semantic retrieval fills its limit with the closer filler posts."""
    similar_creator, blocked_creator = uuid4(), uuid4()
    unreachable_otherwise = {"embedding_value": -0.01, "raw_views": 100, "tags": ("cats",)}

    with db_session_factory() as session:
        user_id = seed_user_with_signal(session)
        similar_user_id = seed_user_with_signal(session)
        session.add_all([
            UserCreatorFeaturesRecord(user_id=similar_user_id, creator_id=similar_creator, affinity_score=1.0, last_updated_at=T0),
            UserCreatorFeaturesRecord(user_id=similar_user_id, creator_id=blocked_creator, affinity_score=1.0, last_updated_at=T0),
            UserCreatorFeaturesRecord(user_id=user_id, creator_id=similar_creator, affinity_score=1.0, last_updated_at=T0),
            PostTagFeaturesRecord(user_id=user_id, tag_name="cats", affinity_score=1.0, last_updated_at=T0),
            FollowRecord(follower_id=user_id, followed_id=similar_creator, created_at=T0),
            BlockRecord(blocker_id=user_id, blocked_id=blocked_creator, created_at=T0),
        ])

        collaborative = [seed_post(session, creator_id=similar_creator, **unreachable_otherwise) for _ in range(5)]
        excluded = seed_post(session, creator_id=similar_creator, **unreachable_otherwise)
        seen = seed_post(session, creator_id=similar_creator, **unreachable_otherwise)
        session.add(UserPostInteractionsRecord(post_id=seen, user_id=user_id, ever_seen=True))
        blocked = seed_post(session, creator_id=blocked_creator, **unreachable_otherwise)
        filler = [
            seed_post(session, embedding_value=0.01, watch_time_percent=0.1) for _ in range(SEMANTIC_POST_LIMIT)
        ]
        session.commit()

    return SimpleNamespace(
        user_id=user_id, collaborative=collaborative, excluded=excluded, seen=seen, blocked=blocked, filler=filler,
    )


async def test_user_with_history_receives_the_posts_of_the_collaborative_path(stub, db_session_factory):
    # Arrange
    history = seed_user_with_history(db_session_factory)

    # Act
    response = await stub.GetRecommendedFeed(
        recommendation_pb2.GetRecommendedFeedRequest(
            user_id=str(history.user_id),
            excluded_post_ids=[str(history.excluded)],
            reason=recommendation_pb2.LOW_WATERMARK,
        ),
        timeout=60,
    )

    # Assert
    post_ids = list(response.post_ids)
    assert len(post_ids) == 30
    assert len(set(post_ids)) == 30
    assert {str(post_id) for post_id in history.collaborative} <= set(post_ids)
    assert not {str(history.excluded), str(history.seen), str(history.blocked)} & set(post_ids)
    # The follow, creator and tag affinities reached the ranking: no filler post has any,
    # and all of them have a better watch time
    assert post_ids[0] in {str(post_id) for post_id in history.collaborative}


async def test_new_user_with_no_features_row_receives_posts_over_grpc(stub, db_session_factory):
    post_ids = seed_ranked_posts(db_session_factory, 5)

    response = await stub.GetRecommendedFeed(
        recommendation_pb2.GetRecommendedFeedRequest(
            user_id=str(uuid4()), reason=recommendation_pb2.USER_REGISTERED
        ),
        timeout=30,
    )

    assert set(response.post_ids) == {str(post_id) for post_id in post_ids}


async def test_new_user_with_no_features_row_receives_posts_over_rabbitmq(amqp, db_session_factory):
    post_ids = seed_ranked_posts(db_session_factory, 5)
    user_id = uuid4()

    await amqp.publish(feed_request_body(user_id=user_id, reason="user_registered", current_feed_size=0))

    body = json.loads((await amqp.next_response()).body)
    assert body["userId"] == str(user_id)
    assert set(body["posts"]) == {str(post_id) for post_id in post_ids}


async def test_no_eligible_posts_is_an_ok_empty_grpc_response(stub, db_session_factory):
    response = await stub.GetRecommendedFeed(
        recommendation_pb2.GetRecommendedFeedRequest(user_id=str(uuid4())), timeout=30
    )

    assert list(response.post_ids) == []


@pytest.mark.parametrize(
    "request_fields",
    [
        {"user_id": "not-a-uuid"},
        {"user_id": ""},
        {"user_id": "019f0000-0000-7000-8000-000000000001", "excluded_post_ids": ["not-a-uuid"]},
    ],
)
async def test_malformed_ids_are_invalid_argument(stub, db_session_factory, request_fields):
    with pytest.raises(grpc.aio.AioRpcError) as error:
        await stub.GetRecommendedFeed(recommendation_pb2.GetRecommendedFeedRequest(**request_fields), timeout=30)

    assert error.value.code() == grpc.StatusCode.INVALID_ARGUMENT


async def test_feed_request_produces_recommended_post_sent_through_the_outbox(amqp, db_session_factory):
    # Arrange
    user_id = seed_user(db_session_factory)
    post_ids = seed_ranked_posts(db_session_factory, 6)
    event_id, correlation_id = uuid4(), uuid4()

    # Act
    await amqp.publish(feed_request_body(
        event_id=event_id, correlation_id=correlation_id, user_id=user_id, excluded_post_ids=[post_ids[0]],
    ))
    message = await amqp.next_response()

    # Assert: the wire contract, literally
    assert message.exchange == "x.feed.events"
    assert message.routing_key == "rk.post.feed.recommended.sent"
    assert message.correlation_id == str(correlation_id)
    assert message.type == "RecommendedPostSent"
    body = json.loads(message.body)
    assert set(body) == {"id", "correlationId", "occurredAt", "userId", "posts"}
    assert body["correlationId"] == str(correlation_id)
    assert body["userId"] == str(user_id)
    assert body["posts"] == [str(post_id) for post_id in post_ids[1:]]
    assert datetime.fromisoformat(body["occurredAt"]).tzinfo is not None
    assert message.message_id == body["id"]

    # Assert: it went through the outbox, and the request is marked processed
    [row] = outbox_rows(db_session_factory)
    assert str(row["id"]) == body["id"]
    await wait_until(lambda: outbox_rows(db_session_factory)[0]["status"] == "PROCESSED")
    with db_session_factory() as session:
        assert session.execute(
            text("SELECT count(*) FROM processed_events WHERE event_id = :id AND correlation_id = :correlation_id"),
            {"id": event_id, "correlation_id": correlation_id},
        ).scalar_one() == 1

    # Assert: only the feed queue got it. The post listener would dead-letter a feed
    # request, had the routing key stayed bound to the post queue as in older versions
    await asyncio.sleep(0.5)
    assert await amqp.drain(POST_DLQ) == []


async def test_no_eligible_posts_still_publishes_an_empty_response(amqp, db_session_factory):
    correlation_id, user_id = uuid4(), uuid4()

    await amqp.publish(feed_request_body(correlation_id=correlation_id, user_id=user_id))

    body = json.loads((await amqp.next_response()).body)
    assert body["posts"] == []
    assert body["correlationId"] == str(correlation_id)
    assert body["userId"] == str(user_id)


async def test_null_excluded_post_ids_from_go_are_no_exclusions(amqp, db_session_factory):
    post_ids = seed_ranked_posts(db_session_factory, 2)
    body = json.loads(feed_request_body())
    body["excludedPostIds"] = None

    await amqp.publish(json.dumps(body).encode())

    response = json.loads((await amqp.next_response()).body)
    assert set(response["posts"]) == {str(post_id) for post_id in post_ids}


async def test_redelivered_request_publishes_only_one_response(amqp, db_session_factory):
    # Arrange
    seed_ranked_posts(db_session_factory, 2)
    body = feed_request_body()

    other_correlation_id = uuid4()

    # Act: the same message twice, the second one after the first was fully processed.
    # The consumer takes one at a time, so an answer to a later message proves the
    # duplicate was already consumed
    await amqp.publish(body)
    first_response = json.loads((await amqp.next_response()).body)
    await amqp.publish(body)
    await amqp.publish(feed_request_body(correlation_id=other_correlation_id))
    next_response = json.loads((await amqp.next_response()).body)

    # Assert: acknowledged, without a second response for it
    assert first_response["correlationId"] == json.loads(body)["correlationId"]
    assert next_response["correlationId"] == str(other_correlation_id)
    assert await amqp.drain(FEED_DLQ) == []
    assert len(outbox_rows(db_session_factory)) == 2


@pytest.mark.parametrize(
    "body",
    [
        b"this is not json",
        b'{"id": "not-a-uuid"}',
        # No userId
        b'{"id":"019f0000-0000-7000-8000-000000000001","correlationId":"019f0000-0000-7000-8000-000000000002",'
        b'"occurredAt":"2026-11-04T10:00:00Z","reason":"disconnect","currentFeedSize":1,"excludedPostIds":[]}',
        # The proto reason is not a valid event reason
        b'{"id":"019f0000-0000-7000-8000-000000000001","correlationId":"019f0000-0000-7000-8000-000000000002",'
        b'"occurredAt":"2026-11-04T10:00:00Z","userId":"019f0000-0000-7000-8000-000000000003",'
        b'"reason":"FEED_EMPTY","currentFeedSize":1,"excludedPostIds":[]}',
    ],
)
async def test_malformed_request_lands_in_the_dlq_without_retries(amqp, db_session_factory, body):
    # Act
    started_at = time.monotonic()
    await amqp.publish(body)
    message = await get_message(amqp.channel, FEED_DLQ)

    # Assert: faster than the 2 s the retry middleware waits before its first retry
    assert time.monotonic() - started_at < 2.0
    assert message.body == body
    assert await amqp.drain(amqp.responses.name) == []
    assert outbox_rows(db_session_factory) == []


async def test_post_events_reach_their_listener_from_the_real_subscriber(amqp, db_session_factory):
    # Arrange: literal JSON as post-command-service writes it
    post_id, event_id, correlation_id = uuid4(), uuid4(), uuid4()
    body = json.dumps({
        "id": str(event_id),
        "correlationId": str(correlation_id),
        "occurredAt": "2026-11-04T10:00:00Z",
        "postId": str(post_id),
        "userId": str(uuid4()),
        "postType": "BASIC",
        "description": "a post",
        "taggedUsers": [],
        "postTags": ["python"],
        "createdAt": "2026-11-04T10:00:00Z",
        "updatedAt": "2026-11-04T10:00:00Z",
    }).encode()

    # Act
    await amqp.publish(body, exchange="x.post.events", routing_key="rk.post.created")

    # Assert
    def post_was_stored():
        with db_session_factory() as session:
            return session.execute(
                text("SELECT count(*) FROM post_features WHERE post_id = :id"), {"id": post_id}
            ).scalar_one() == 1

    await wait_until(post_was_stored)
    assert await amqp.drain(POST_DLQ) == []


async def test_user_events_reach_their_listener_from_the_real_subscriber(amqp, db_session_factory):
    # Arrange: literal JSON as social-service writes it
    user_id = uuid4()
    body = json.dumps({
        "id": str(uuid4()),
        "correlationId": str(uuid4()),
        "occurredAt": "2026-11-04T10:00:00Z",
        "userId": str(user_id),
        "username": "ana",
        "email": "ana@example.com",
        "bio": {"description": "likes python", "socialMedia": {}},
    }).encode()

    # Act
    await amqp.publish(body, exchange="x.user.events", routing_key="rk.user.registered")

    # Assert
    def user_was_stored():
        with db_session_factory() as session:
            return session.execute(
                text("SELECT count(*) FROM user_features WHERE user_id = :id"), {"id": user_id}
            ).scalar_one() == 1

    await wait_until(user_was_stored)
