import logging

import grpc
from grpc_health.v1 import health, health_pb2, health_pb2_grpc

from grpc_server.metrics_interceptor import ServerMetricsInterceptor
from grpc_server.recommendation_servicer import RecommendationServicer
from recommendation.v1 import recommendation_pb2, recommendation_pb2_grpc
from shared.config.env import positive_int_env

logger = logging.getLogger(__name__)

GRPC_PORT = positive_int_env("GRPC_PORT", 50051)
# Below terminationGracePeriodSeconds (45 s in the chart)
GRPC_SHUTDOWN_GRACE_S = positive_int_env("GRPC_SHUTDOWN_GRACE_S", 20)

SERVICE_NAME = recommendation_pb2.DESCRIPTOR.services_by_name["RecommendationService"].full_name


class RecommendationGrpcServer:
    """grpc.aio server on the FastStream event loop: started and stopped by the
    app's lifespan hooks, next to the outbox relay."""

    def __init__(
        self,
        servicer: RecommendationServicer,
        *,
        port: int = GRPC_PORT,
        shutdown_grace_s: float = GRPC_SHUTDOWN_GRACE_S,
    ):
        self._servicer = servicer
        self._port = port
        self._shutdown_grace_s = shutdown_grace_s
        self._server: grpc.aio.Server | None = None
        self._health: health.aio.HealthServicer | None = None

    async def start(self) -> None:
        # Without reuseport a taken port fails here instead of silently sharing traffic
        self._server = grpc.aio.server(
            interceptors=(ServerMetricsInterceptor(),),
            options=(("grpc.so_reuseport", 0),),
        )
        recommendation_pb2_grpc.add_RecommendationServiceServicer_to_server(self._servicer, self._server)

        self._health = health.aio.HealthServicer()
        health_pb2_grpc.add_HealthServicer_to_server(self._health, self._server)

        if self._server.add_insecure_port(f"[::]:{self._port}") == 0:
            raise RuntimeError(f"Could not bind the gRPC port {self._port}")

        await self._server.start()
        # "" is the server's overall status, the one a client gets from an empty request
        for service in ("", SERVICE_NAME):
            await self._health.set(service, health_pb2.HealthCheckResponse.SERVING)
        logger.info("gRPC server started: port=%s", self._port)

    async def stop(self) -> None:
        if self._server is None:
            return
        await self._health.enter_graceful_shutdown()
        # Refuses new calls at once; the ones in flight get the grace period
        await self._server.stop(self._shutdown_grace_s)
        self._server = None
        logger.info("gRPC server stopped")
