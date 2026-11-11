import time
from collections.abc import Awaitable, Callable

import grpc
from opentelemetry import metrics

METER_NAME = "recommendation-service.grpc"
# Seconds, so that it reads like the HTTP server durations of the other services
DURATION_BUCKETS_S = (0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30)


class ServerMetricsInterceptor(grpc.aio.ServerInterceptor):
    """Records rpc.server.call.duration for every unary call, with the service, the
    method and the gRPC status code, so that the rate, the error rate and the latency
    of the server are available as metrics. Health checks are left out: the probes
    would hide the real traffic. Streaming calls pass through unmeasured."""

    def __init__(
        self,
        excluded_services: tuple[str, ...] = ("grpc.health.v1.Health",),
        meter_provider: metrics.MeterProvider | None = None,
    ):
        self._excluded_services = excluded_services
        self._duration = metrics.get_meter(METER_NAME, meter_provider=meter_provider).create_histogram(
            "rpc.server.call.duration",
            unit="s",
            description="Duration of a served gRPC call.",
            explicit_bucket_boundaries_advisory=DURATION_BUCKETS_S,
        )

    async def intercept_service(
        self,
        continuation: Callable[[grpc.HandlerCallDetails], Awaitable[grpc.RpcMethodHandler]],
        handler_call_details: grpc.HandlerCallDetails,
    ) -> grpc.RpcMethodHandler:
        handler = await continuation(handler_call_details)
        # "/package.Service/Method"
        _, service, method = handler_call_details.method.split("/", 2)
        if handler is None or handler.unary_unary is None or service in self._excluded_services:
            return handler

        behavior = handler.unary_unary

        async def measured(request, context: grpc.aio.ServicerContext):
            started = time.perf_counter()
            failed = False
            try:
                return await behavior(request, context)
            except BaseException:
                failed = True
                raise
            finally:
                self._duration.record(
                    time.perf_counter() - started,
                    {
                        "rpc.system": "grpc",
                        "rpc.service": service,
                        "rpc.method": method,
                        "rpc.grpc.status_code": _status_code(context, failed).name,
                    },
                )

        return grpc.unary_unary_rpc_method_handler(
            measured,
            request_deserializer=handler.request_deserializer,
            response_serializer=handler.response_serializer,
        )


def _status_code(context: grpc.aio.ServicerContext, failed: bool) -> grpc.StatusCode:
    # context.abort() and set_code() leave their code here; an exception that set
    # none reaches the client as UNKNOWN
    code = context.code()
    if code is not None:
        return code
    return grpc.StatusCode.UNKNOWN if failed else grpc.StatusCode.OK
