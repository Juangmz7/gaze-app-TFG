from observability.logging_config import configure_log_context, configure_logging

# Before the other imports: building the Container already logs
configure_logging()

from faststream.asgi import AsgiFastStream, AsgiResponse, get, make_ping_asgi  # noqa: E402

# Imported for its effect: registers the subscribers on the broker the app starts
import rabbitmq.config.subscribers  # noqa: E402, F401
from rabbitmq.config.broker import broker  # noqa: E402
from rabbitmq.config.declarables import (  # noqa: E402
    configure_rabbitmq_declarables,
)
from rabbitmq.worker.outbox_relay import OutboxRelayWorker  # noqa: E402
from shared.config.containers import container  # noqa: E402
from shared.config.database import SQLAlchemySessionProvider  # noqa: E402
from shared.repository.impl.sql_alchemy_outbox_event_repository import SqlAlchemyOutboxEventRepository  # noqa: E402

configure_log_context()


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

relay_worker = OutboxRelayWorker(SqlAlchemyOutboxEventRepository(SQLAlchemySessionProvider()), broker)


# The subscribers open their queues (declare=False) when the broker starts, so
# the topology has to exist before that: on a new broker they would fail otherwise.
@app.on_startup
async def configure_rabbitmq() -> None:
    await broker.connect()
    await configure_rabbitmq_declarables(broker)


@app.after_startup
async def start_background_services() -> None:
    relay_worker.start()
    await container.recommendation_grpc_server.start()


@app.on_shutdown
async def stop_background_services() -> None:
    await container.recommendation_grpc_server.stop()
    await relay_worker.stop()


# After the broker stopped: an in-flight feed request may still need the pool until then
@app.after_shutdown
async def stop_pipeline_workers() -> None:
    await container.pipeline_worker_pool.shutdown()
