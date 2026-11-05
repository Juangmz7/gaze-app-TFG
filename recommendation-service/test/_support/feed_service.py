"""Helpers for the tests that talk to the real service over gRPC and AMQP."""
from __future__ import annotations

import asyncio
import json
import os
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from collections.abc import Iterator
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from uuid import UUID, uuid4

import aio_pika
import grpc
from grpc_health.v1 import health_pb2, health_pb2_grpc

from pipeline.entity.post.post_features_entity import PostFeaturesRecord
from pipeline.entity.post.post_interaction_features_entity import PostInteractionFeaturesRecord
from pipeline.entity.user.user_features_entity import UserFeaturesRecord
from recommendation.v1 import recommendation_pb2_grpc

SERVICE_ROOT = Path(__file__).resolve().parents[2]
STARTUP_TIMEOUT_S = 180
T0 = datetime(2026, 1, 1, tzinfo=timezone.utc)

FEED_EXCHANGE = "x.feed.events"
FEED_EXHAUSTED_RK = "rk.post.feed.exhausted"
RECOMMENDED_SENT_RK = "rk.post.feed.recommended.sent"
FEED_QUEUE = "q.recommendation-service.feed"
FEED_DLQ = "q.recommendation-service.feed-dlq"
POST_QUEUE = "q.recommendation-service.post"
POST_DLQ = "q.recommendation-service.post-dlq"


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def service_env(**overrides: str) -> dict[str, str]:
    """This machine's environment without what would change the service's logging,
    so the tests see what a deployment that only sets the chart's variables sees."""
    return {
        **{
            name: value for name, value in os.environ.items()
            if name != "LOG_LEVEL" and not name.startswith("OTEL_")
        },
        "PYTHONPATH": os.pathsep.join([str(SERVICE_ROOT / "src"), str(SERVICE_ROOT / "test")]),
        "PYTHONDONTWRITEBYTECODE": "1",
        **overrides,
    }


class RunningService:
    def __init__(self, grpc_port: int, http_port: int, stdout_path: Path):
        self.grpc_port = grpc_port
        self.grpc_target = f"127.0.0.1:{grpc_port}"
        self.http_base = f"http://127.0.0.1:{http_port}"
        self.stdout_path = stdout_path

    def stdout_lines(self) -> list[str]:
        """What the service printed to stdout so far."""
        return self.stdout_path.read_text().splitlines()


@contextmanager
def run_service(postgres_url: str, rabbitmq_url: str, **env: str) -> Iterator[RunningService]:
    """The real entrypoint in a subprocess, until gRPC and the readiness probe answer."""
    grpc_port, http_port = free_port(), free_port()
    # Files and not pipes: nobody reads a pipe while the service runs, and a full
    # one would block the service on its next log line
    with tempfile.TemporaryDirectory(prefix="recommendation-service-") as output_dir:
        stdout_path, stderr_path = Path(output_dir) / "stdout.log", Path(output_dir) / "stderr.log"
        with stdout_path.open("w") as stdout, stderr_path.open("w") as stderr:
            process = subprocess.Popen(
                [sys.executable, str(SERVICE_ROOT / "test" / "_support" / "run_real_service.py"),
                 "--host", "127.0.0.1", "--port", str(http_port)],
                cwd=SERVICE_ROOT,
                env=service_env(DATABASE_URL=postgres_url, RABBITMQ_URL=rabbitmq_url, GRPC_PORT=str(grpc_port), **env),
                stdout=stdout,
                stderr=stderr,
            )
        service = RunningService(grpc_port, http_port, stdout_path)
        try:
            _wait_until_ready(process, service, stderr_path)
            yield service
        finally:
            process.terminate()
            try:
                process.wait(timeout=45)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()


def _wait_until_ready(process: subprocess.Popen, service: RunningService, stderr_path: Path) -> None:
    deadline = time.monotonic() + STARTUP_TIMEOUT_S
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"The service exited during startup:\n{_output(service, stderr_path)}")
        # Both: the probe answers as soon as the broker is up, just before gRPC starts
        if http_status(service, "/healthz/ready") == 204 and _grpc_port_is_open(service):
            return
        time.sleep(0.5)
    process.kill()
    process.wait()
    raise RuntimeError(f"The service did not start in time:\n{_output(service, stderr_path)}")


def _grpc_port_is_open(service: RunningService) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", service.grpc_port), timeout=1):
            return True
    except OSError:
        return False


def _output(service: RunningService, stderr_path: Path) -> str:
    return "\n".join([*service.stdout_lines(), stderr_path.read_text()])


def http_status(service: RunningService, path: str, timeout: float = 2.0) -> int | None:
    try:
        with urllib.request.urlopen(service.http_base + path, timeout=timeout) as response:
            return response.status
    except urllib.error.HTTPError as exc:
        return exc.code
    except OSError:
        return None


async def grpc_channel_stub(service: RunningService):
    channel = grpc.aio.insecure_channel(service.grpc_target)
    return channel, recommendation_pb2_grpc.RecommendationServiceStub(channel)


async def health_status(service: RunningService, service_name: str = "") -> int:
    async with grpc.aio.insecure_channel(service.grpc_target) as channel:
        response = await health_pb2_grpc.HealthStub(channel).Check(
            health_pb2.HealthCheckRequest(service=service_name), timeout=5
        )
        return response.status


def seed_post(
    session,
    *,
    post_id: UUID | None = None,
    creator_id: UUID | None = None,
    tags: tuple[str, ...] = ("python",),
    embedding_value: float = 0.1,
    watch_time_percent: float = 0.0,
    raw_views: int = 0,
    created_at: datetime = T0,
) -> UUID:
    """A post, by its own creator unless one is given, eligible for every user."""
    post_id = post_id or uuid4()
    session.add(PostFeaturesRecord(
        post_id=post_id,
        creator_id=creator_id or uuid4(),
        tags=list(tags),
        tagged_users_ids=[],
        semantic_embedding=[embedding_value] * 1024,
        created_at=created_at,
    ))
    session.add(PostInteractionFeaturesRecord(
        post_id=post_id,
        raw_watch_time_average_percent=watch_time_percent,
        raw_views=raw_views,
        decayed_engagement_score=watch_time_percent,
        last_updated_at=T0,
    ))
    return post_id


def seed_user_with_signal(session, user_id: UUID | None = None) -> UUID:
    user_id = user_id or uuid4()
    session.add(UserFeaturesRecord(
        user_id=user_id,
        semantic_embedding=[0.1] * 1024,
        last_updated_at=T0,
        has_semantic_signal=True,
    ))
    return user_id


def feed_request_body(
    *,
    event_id: UUID | None = None,
    correlation_id: UUID | None = None,
    user_id: UUID | None = None,
    excluded_post_ids: list[UUID] | None = None,
    reason: str = "disconnect",
    current_feed_size: int = 3,
) -> bytes:
    """Literal camelCase JSON, as post-query-service writes it."""
    return json.dumps({
        "id": str(event_id or uuid4()),
        "correlationId": str(correlation_id or uuid4()),
        "occurredAt": "2026-11-04T10:00:00.123456Z",
        "userId": str(user_id or uuid4()),
        "reason": reason,
        "currentFeedSize": current_feed_size,
        "excludedPostIds": [str(post_id) for post_id in (excluded_post_ids or [])],
    }).encode()


class Amqp:
    """A test-side AMQP client: publishes requests and watches the feed responses."""

    def __init__(self, connection, channel, responses):
        self.connection = connection
        self.channel = channel
        self.responses = responses

    @classmethod
    async def connect(cls, rabbitmq_url: str) -> Amqp:
        connection = await aio_pika.connect_robust(rabbitmq_url)
        channel = await connection.channel()
        exchange = await channel.get_exchange(FEED_EXCHANGE)
        responses = await channel.declare_queue(exclusive=True)
        await responses.bind(exchange, routing_key=RECOMMENDED_SENT_RK)
        return cls(connection, channel, responses)

    async def publish(self, body: bytes, *, exchange: str = FEED_EXCHANGE, routing_key: str = FEED_EXHAUSTED_RK) -> None:
        target = await self.channel.get_exchange(exchange)
        await target.publish(
            aio_pika.Message(body, content_type="application/json", delivery_mode=aio_pika.DeliveryMode.PERSISTENT),
            routing_key=routing_key,
        )

    async def next_response(self, timeout: float = 30.0) -> aio_pika.abc.AbstractIncomingMessage:
        return await get_message(self.channel, self.responses.name, timeout)

    async def drain(self, queue_name: str) -> list[aio_pika.abc.AbstractIncomingMessage]:
        """Takes every message of a queue nobody consumes. Reads them instead of asking
        for the count, because the passive declare reported 0 with a message waiting."""
        queue = await self.channel.declare_queue(queue_name, passive=True)
        messages = []
        while (message := await queue.get(fail=False, no_ack=True, timeout=5)) is not None:
            messages.append(message)
        return messages

    async def purge(self, *queue_names: str) -> None:
        for queue_name in queue_names:
            queue = await self.channel.declare_queue(queue_name, passive=True)
            await queue.purge()

    async def close(self) -> None:
        await self.connection.close()


async def get_message(channel, queue_name: str, timeout: float = 30.0):
    queue = await channel.declare_queue(queue_name, passive=True)
    async with asyncio.timeout(timeout):
        while True:
            message = await queue.get(fail=False, timeout=5)
            if message is not None:
                await message.ack()
                return message
            await asyncio.sleep(0.02)
