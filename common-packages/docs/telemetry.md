# Telemetry Package

## What it does
The `telemetry` package provides centralized tracing and logging configuration for microservices. It connects the application to an OpenTelemetry (OTel) Collector to export traces using gRPC. Additionally, it configures Go's standard structured logger (`log/slog`) with a custom handler (`OTelLogHandler`) that automatically extracts the OpenTelemetry `trace_id` and `span_id` from the context and injects them into all log records. This enables seamless correlation between application logs and distributed traces. 

The package provides:
- `InitTracer`: A function to configure the OTel gRPC exporter, register the `TracerProvider`, and set up text map propagation (for distributed tracing across HTTP calls).
- `OTelLogHandler`: A custom `slog.Handler` that adds tracing metadata to log attributes.
- A default logger initialization injecting the `OTelLogHandler` to output JSON-formatted logs.

## Packages used
- `context`: To pass execution context and extract active spans.
- `fmt`: For error formatting.
- `log/slog`: For structured logging.
- `os`: To write logs to standard output.
- `go.opentelemetry.io/otel`: The core OpenTelemetry API.
- `go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc`: To export traces using OTLP over gRPC.
- `go.opentelemetry.io/otel/propagation`: For Trace Context and Baggage propagation.
- `go.opentelemetry.io/otel/sdk/resource`: To attach metadata (like service name and environment) to traces.
- `go.opentelemetry.io/otel/sdk/trace`: The OpenTelemetry SDK for trace management (e.g., batching, sampling).
- `go.opentelemetry.io/otel/semconv/v1.26.0`: Semantic conventions for resource attributes.
- `go.opentelemetry.io/otel/trace`: The OpenTelemetry Trace API for span extraction.

## Why
In a microservice architecture, observability is crucial for debugging and monitoring distributed requests. 
- **OpenTelemetry via gRPC:** Exporting traces directly to an OTel Collector provides a standardized and vendor-agnostic way to collect telemetry data. We use gRPC without TLS (insecure) because the communication typically happens within a secure internal network or Kubernetes cluster, reducing overhead.
- **Trace Context Propagation:** Registering a global text map propagator allows our Go services to seamlessly read trace identifiers sent by other services (e.g., Spring Boot applications or testing tools like JMeter) via HTTP headers, and continue the same trace.
- **Log Correlation:** By wrapping the `slog.JSONHandler` with a custom `OTelLogHandler`, we ensure that every log message emitted includes the active `trace_id` and `span_id`. This allows monitoring platforms (like Grafana, ELK, or Datadog) to stitch logs and traces together automatically, dramatically simplifying troubleshooting efforts across different services.
