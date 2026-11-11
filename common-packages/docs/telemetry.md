# Telemetry Package

`github.com/Juangmz7/TFG/common-packages/go-utils/telemetry`

## What it does
The `telemetry` package wires OpenTelemetry (OTel Go SDK v1.44.0) into Go services with a single call:

- **Traces, metrics and logs** exported to the OTel Collector over **OTLP/gRPC**.
- A **parent-based sampler**: root spans are sampled at a configurable ratio, while spans with a parent always follow the parent's decision, so a trace started by another service is never cut in half.
- A shared **resource** (`service.name`, `deployment.environment.name`, host, process, container, SDK attributes) that also honours `OTEL_SERVICE_NAME` and `OTEL_RESOURCE_ATTRIBUTES`, like the Java and Python services.
- **Logs** written as JSON to stdout with `trace_id` / `span_id`, and also sent over OTLP through the `otelslog` bridge.
- The global **W3C TraceContext + Baggage** propagator, plus carriers and helpers to propagate context through **RabbitMQ headers** (`amqp091.Table`) and **Watermill message metadata** (`message.Metadata`).

## Usage

```go
func run() error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	shutdownTelemetry, err := telemetry.Init(ctx, telemetry.Config{
		ServiceName: "post-query-service", // or OTEL_SERVICE_NAME
	})
	if err != nil {
		return err
	}
	// Registered first, so it runs last and flushes spans/logs from the whole shutdown.
	defer func() {
		flushCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := shutdownTelemetry(flushCtx); err != nil {
			slog.Error("telemetry shutdown failed", "error", err)
		}
	}()
	// ...
}
```

`Init` sets the global `TracerProvider`, `MeterProvider`, `LoggerProvider`, text map propagator and the default `slog` logger. It returns a `ShutdownFunc` that flushes and stops all three providers (traces, then metrics, then logs). Call it once with a bounded context; later calls are no-ops. If `Init` returns an error, nothing global has been changed.

### `Config`

Every field is optional. An explicit (non-zero) field always wins over its environment variable; otherwise the standard `OTEL_*` variable is used, then the default.

| Field | Meaning | Fallback |
|---|---|---|
| `ServiceName` | `service.name` | `OTEL_SERVICE_NAME` → `service.name` in `OTEL_RESOURCE_ATTRIBUTES` → `unknown_service:<exe>` |
| `ServiceVersion` | `service.version` | `OTEL_RESOURCE_ATTRIBUTES` |
| `Environment` | `deployment.environment.name` | `OTEL_RESOURCE_ATTRIBUTES` |
| `Endpoint` | OTLP gRPC collector endpoint for all signals | per signal: `OTEL_EXPORTER_OTLP_{TRACES,METRICS,LOGS}_ENDPOINT` → `OTEL_EXPORTER_OTLP_ENDPOINT` → `http://localhost:4317` |
| `SamplingRatio` | ratio for root spans, in `(0, 1]` | `OTEL_TRACES_SAMPLER_ARG` → `1.0` |
| `LogLevel` | minimum level for stdout and OTLP logs | `slog.LevelInfo` |
| `LogOutput` | destination of the JSON logs | `os.Stdout` |

### Environment variables

| Variable | Effect |
|---|---|
| `OTEL_SERVICE_NAME` | Service name (if `Config.ServiceName` is empty). |
| `OTEL_RESOURCE_ATTRIBUTES` | Extra resource attributes, e.g. `deployment.environment.name=prod,service.version=1.2.0`. This is how the k8s charts set the environment. |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | Collector endpoint for all signals. |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`, `..._METRICS_ENDPOINT`, `..._LOGS_ENDPOINT` | Per-signal override of the above. |
| `OTEL_TRACES_SAMPLER_ARG` | Root sampling ratio. |
| `OTEL_TRACES_EXPORTER`, `OTEL_METRICS_EXPORTER`, `OTEL_LOGS_EXPORTER` | Set to `none` to disable exporting that signal (local runs without a collector, tests). Any other value means OTLP. Traces are still sampled and propagated when disabled. |
| `OTEL_METRIC_EXPORT_INTERVAL` | Metric export interval in ms (read by the SDK, default 60000). |
| `OTEL_EXPORTER_OTLP_HEADERS`, `..._TIMEOUT`, `..._COMPRESSION`, `..._CERTIFICATE` (and per-signal variants) | Read by the OTLP exporters themselves. |

`OTEL_TRACES_SAMPLER` is **ignored**: the sampler is always `parentbased_traceidratio`.

### Endpoint formats
Both forms are accepted, in `Config.Endpoint` and in the env vars:

- **URL**, the form every other service uses: `http://gaze-collector.observability.svc.cluster.local:4317` (plaintext) or `https://...:4317` (TLS).
- **host:port**: `gaze-collector.observability.svc.cluster.local:4317`, always plaintext.

Other schemes or a missing host make `Init` return an error. This package speaks **gRPC**, so point it at the collector's gRPC port (**4317**), not the OTLP/HTTP port (4318).

### Sampling
The sampler is `ParentBased(TraceIDRatioBased(ratio))`:

- A span with a sampled parent (local or remote, e.g. from a `traceparent` header) is always sampled; one with an unsampled parent is always dropped.
- A root span is sampled with probability `ratio`.

**Invalid ratios are an error, not a silent fallback.** A resolved ratio outside `(0, 1]` (negative, `0` from `OTEL_TRACES_SAMPLER_ARG`, above 1, NaN, or a non-numeric env value) makes `Init` fail, so a misconfigured deployment fails at startup instead of dropping or over-sampling traces. `Config.SamplingRatio == 0` means "unset" and falls back to the env var and then to `1.0`. To export no traces at all, use `OTEL_TRACES_EXPORTER=none`.

### Resource
Built with `resource.New`, detectors in this order (later ones override earlier ones):

1. `WithService`: `service.instance.id` and the `unknown_service:<exe>` fallback name.
2. `WithTelemetrySDK`, `WithHost`, `WithContainer`, and process attributes (PID, executable name, Go runtime name/version/description). Command-line args and process owner are deliberately excluded because they can leak secrets.
3. `WithFromEnv`: `OTEL_RESOURCE_ATTRIBUTES` and `OTEL_SERVICE_NAME`.
4. Explicit `Config` fields.

The package uses `semconv/v1.41.0`, the newest semantic conventions shipped with OTel Go v1.44.0 and the same version the SDK detectors use, so there is no schema-URL conflict. The environment is emitted as `deployment.environment.name`, matching the Java and Python services. The legacy `deployment.environment` key is not emitted.

## Logging

`Init` installs a default `slog` logger that fans out every record to:

1. **stdout JSON**: `slog.JSONHandler` wrapped in `OTelLogHandler`, which adds `trace_id` and `span_id` attributes when the context carries a valid span.
2. **OTLP**: the `otelslog` bridge (`go.opentelemetry.io/contrib/bridges/otelslog`) writing to the SDK `LoggerProvider`, which attaches the span context to the log record natively.

Use the `*Context` variants (`slog.InfoContext(ctx, ...)`) so both outputs can correlate the record with the active span. `Config.LogLevel` applies to both outputs.

Exported building blocks:

- `InitLogger(level)`: stdout-only JSON logger with trace correlation and no OTLP. Useful for processes that don't call `Init`, or for logging before `Init` runs (`Init` replaces it).
- `NewOTelLogHandler(next)` / `OTelLogHandler`: adds `trace_id` / `span_id` to records sent to `next`.
- `NewFanoutHandler(handlers...)` / `FanoutHandler`: sends each record to every handler enabled for its level. Each handler gets its own clone of the record.

## Context propagation

`Init` (or `SetupPropagator()` on its own) registers the global composite propagator `TraceContext{} + Baggage{}`, so `traceparent`, `tracestate` and `baggage` are read and written in the W3C format used by the Spring Boot and Python services. HTTP is covered by `otelhttp`, which uses the global propagator.

For messaging:

| Carrier | Wraps | Helpers |
|---|---|---|
| `AMQPHeadersCarrier` | `amqp091.Table` | `InjectAMQPHeaders(ctx, headers) amqp091.Table`, `ExtractAMQPHeaders(ctx, headers) context.Context` |
| `MetadataCarrier` | Watermill `message.Metadata` | `InjectMetadata(ctx, md) message.Metadata`, `ExtractMetadata(ctx, md) context.Context` |

Both carriers implement `propagation.TextMapCarrier`. The inject helpers allocate the map when it is nil and return it, so always assign the result. Lookups match keys exactly first and then case-insensitively. AMQP header values may be `string` or `[]byte`.

```go
// Publishing with Watermill
msg := message.NewMessage(watermill.NewUUID(), payload)
msg.Metadata = telemetry.InjectMetadata(ctx, msg.Metadata)

// Consuming with Watermill: continue the producer's trace
ctx := telemetry.ExtractMetadata(msg.Context(), msg.Metadata)
ctx, span := otel.Tracer("post-query-service").Start(ctx, "consume post.created",
	trace.WithSpanKind(trace.SpanKindConsumer))
defer span.End()

// Raw amqp091
pub := amqp091.Publishing{Body: body}
pub.Headers = telemetry.InjectAMQPHeaders(ctx, pub.Headers)

ctx := telemetry.ExtractAMQPHeaders(context.Background(), delivery.Headers)
```

The helpers use the global propagator, so call `Init` (or `SetupPropagator`) first. Otherwise the default no-op propagator injects and extracts nothing.

## Packages used
- `go.opentelemetry.io/otel`, `.../otel/sdk/{trace,metric,log,resource}`: API and SDK providers.
- `go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc`, `.../otlpmetric/otlpmetricgrpc`, `.../otlplog/otlploggrpc`: OTLP/gRPC exporters.
- `go.opentelemetry.io/otel/log/global`: global `LoggerProvider`.
- `go.opentelemetry.io/contrib/bridges/otelslog`: `slog` → OTel logs bridge.
- `go.opentelemetry.io/otel/semconv/v1.41.0`: semantic convention keys.
- `go.opentelemetry.io/otel/propagation`: W3C TraceContext and Baggage.
- `github.com/rabbitmq/amqp091-go`: `amqp091.Table` carrier.
- `github.com/ThreeDotsLabs/watermill/message`: `message.Metadata` carrier.
- `log/slog`: structured logging.

## Why
- **One collector protocol for all signals.** Exporting traces, metrics and logs over OTLP/gRPC to the collector keeps services vendor-neutral and lets the collector route them (Tempo, Prometheus/Mimir, Loki).
- **Parent-based sampling.** A ratio sampler that ignores the parent breaks traces that cross services: the upstream service keeps its spans while this one drops them, or the reverse. Following the parent keeps every trace whole, and the ratio only decides for traces that start here.
- **Standard env vars.** Honouring `OTEL_SERVICE_NAME`, `OTEL_RESOURCE_ATTRIBUTES` and `OTEL_EXPORTER_OTLP_ENDPOINT` lets the k8s charts configure Go services exactly like the Java and Python ones, with the same URL format.
- **Log correlation in both outputs.** stdout JSON keeps `kubectl logs` useful and carries `trace_id`/`span_id`. The OTLP log stream lets Grafana jump between logs and traces.
- **Messaging propagation.** Most of the system talks through RabbitMQ. Without injecting and extracting context on messages, every consumer would start a new, disconnected trace.
