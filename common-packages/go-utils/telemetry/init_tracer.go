package telemetry

import (
	"context"
	"fmt"
	"log/slog"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.26.0"
)

// InitTracer connects the application with the OpenTelemetry Collector and returns a shutdown function.
// It configures the gRPC trace exporter, sets up the resource identity,
// initializes the TracerProvider with batching and sampling strategies, and registers global
// propagators for cross-service trace context propagation.
// Returns:
//   - A shutdown function to be called when the application exits, ensuring all traces are flushed.
//   - An error if the initialization fails.
type InitTracerMetada struct {
	Ctx context.Context
	ServiceName string
	CollectorEndpoint string
	EnviromentType string
	RequestedSamplingRatio float64
}

func InitTracer(itm *InitTracerMetada) (func(context.Context) error, error) {
	slog.Info("Initializing OpenTelemetry Tracer",
		"serviceName", itm.ServiceName,
		"collectorEndpoint", itm.CollectorEndpoint,
		"enviromentType", itm.EnviromentType,
		"requestedSamplingRatio", itm.RequestedSamplingRatio,
	)

	exporter, err := otlptracegrpc.New(itm.Ctx,
		otlptracegrpc.WithInsecure(), // Use insecure for internal network/Kubernetes without TLS
		otlptracegrpc.WithEndpoint(itm.CollectorEndpoint),
	)
	if err != nil {
		return nil, fmt.Errorf("failed to create trace exporter: %w", err)
	}

	res, err := resource.New(itm.Ctx,
		resource.WithAttributes(
			semconv.ServiceNameKey.String(itm.ServiceName),
			semconv.DeploymentEnvironmentKey.String(itm.EnviromentType),
		),
	)
	if err != nil {
		return nil, fmt.Errorf("failed to create resource: %w", err)
	}

	tp := sdktrace.NewTracerProvider(
		sdktrace.WithSampler(sdktrace.TraceIDRatioBased(itm.RequestedSamplingRatio)), 
		sdktrace.WithBatcher(exporter),
		sdktrace.WithResource(res),
	)

	otel.SetTracerProvider(tp)
	
	// This enables the Go application to read and continue trace contexts received via HTTP headers 
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(propagation.TraceContext{}, propagation.Baggage{}))

	slog.Info("OpenTelemetry Tracer initialized successfully")
	return tp.Shutdown, nil
}