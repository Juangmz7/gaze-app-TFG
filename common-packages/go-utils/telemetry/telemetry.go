// Package telemetry wires OpenTelemetry traces, metrics and logs for Go
// services: OTLP/gRPC export, a parent-based sampler, a shared resource,
// JSON stdout logs correlated with traces, and W3C context propagation over
// HTTP, AMQP headers and Watermill metadata.
package telemetry

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"sync"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlplog/otlploggrpc"
	"go.opentelemetry.io/otel/exporters/otlp/otlpmetric/otlpmetricgrpc"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc"
	logglobal "go.opentelemetry.io/otel/log/global"
	sdklog "go.opentelemetry.io/otel/sdk/log"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.41.0"
)

const instrumentationName = "github.com/Juangmz7/TFG/common-packages/go-utils/telemetry"

// ShutdownFunc flushes and stops every provider created by Init.
type ShutdownFunc func(context.Context) error

// Init sets up the global TracerProvider, MeterProvider and LoggerProvider
// exporting over OTLP/gRPC, the global TraceContext+Baggage propagator, and
// the default slog logger (JSON on stdout + OTLP). See Config for the
// configuration sources and their precedence.
//
// The returned ShutdownFunc flushes and stops all three providers (traces,
// then metrics, then logs, so shutdown logs are still exported). Call it once
// on exit with a bounded context; later calls are no-ops. On error nothing
// global is modified.
func Init(ctx context.Context, cfg Config) (ShutdownFunc, error) {
	ratio, err := resolveSamplingRatio(cfg.SamplingRatio)
	if err != nil {
		return nil, err
	}

	// Resolve every endpoint before creating anything, so a bad value fails
	// without having to tear down half-built providers.
	tracesOn, metricsOn, logsOn := exporterEnabled(EnvTracesExporter), exporterEnabled(EnvMetricsExporter), exporterEnabled(EnvLogsExporter)
	var tracesURL, metricsURL, logsURL string
	if tracesOn {
		if tracesURL, err = resolveEndpoint(cfg.Endpoint, EnvExporterTracesEndpoint); err != nil {
			return nil, err
		}
	}
	if metricsOn {
		if metricsURL, err = resolveEndpoint(cfg.Endpoint, EnvExporterMetricsEndpoint); err != nil {
			return nil, err
		}
	}
	if logsOn {
		if logsURL, err = resolveEndpoint(cfg.Endpoint, EnvExporterLogsEndpoint); err != nil {
			return nil, err
		}
	}

	res, err := newResource(ctx, cfg)
	if err != nil {
		return nil, err
	}

	var shutdowns []func(context.Context) error
	shutdownAll := func(ctx context.Context) error {
		var errs []error
		for _, fn := range shutdowns {
			errs = append(errs, fn(ctx))
		}
		return errors.Join(errs...)
	}
	fail := func(err error) (ShutdownFunc, error) {
		return nil, errors.Join(err, shutdownAll(ctx))
	}

	// Traces. The sampler is installed even when export is disabled so the
	// sampling decision is still propagated downstream.
	traceOpts := []sdktrace.TracerProviderOption{
		sdktrace.WithResource(res),
		sdktrace.WithSampler(newSampler(ratio)),
	}
	if tracesOn {
		exp, err := otlptracegrpc.New(ctx, otlptracegrpc.WithEndpointURL(tracesURL))
		if err != nil {
			return fail(fmt.Errorf("telemetry: create trace exporter: %w", err))
		}
		traceOpts = append(traceOpts, sdktrace.WithBatcher(exp))
	}
	tp := sdktrace.NewTracerProvider(traceOpts...)
	shutdowns = append(shutdowns, tp.Shutdown)

	// Metrics. The export interval honours OTEL_METRIC_EXPORT_INTERVAL.
	metricOpts := []sdkmetric.Option{sdkmetric.WithResource(res)}
	if metricsOn {
		exp, err := otlpmetricgrpc.New(ctx, otlpmetricgrpc.WithEndpointURL(metricsURL))
		if err != nil {
			return fail(fmt.Errorf("telemetry: create metric exporter: %w", err))
		}
		metricOpts = append(metricOpts, sdkmetric.WithReader(sdkmetric.NewPeriodicReader(exp)))
	}
	mp := sdkmetric.NewMeterProvider(metricOpts...)
	shutdowns = append(shutdowns, mp.Shutdown)

	// Logs.
	var lp *sdklog.LoggerProvider
	if logsOn {
		exp, err := otlploggrpc.New(ctx, otlploggrpc.WithEndpointURL(logsURL))
		if err != nil {
			return fail(fmt.Errorf("telemetry: create log exporter: %w", err))
		}
		lp = sdklog.NewLoggerProvider(
			sdklog.WithResource(res),
			sdklog.WithProcessor(sdklog.NewBatchProcessor(exp)),
		)
		shutdowns = append(shutdowns, lp.Shutdown)
	}

	otel.SetTracerProvider(tp)
	otel.SetMeterProvider(mp)
	if lp != nil {
		logglobal.SetLoggerProvider(lp)
	}
	SetupPropagator()
	slog.SetDefault(slog.New(newLogHandler(cfg, lp)))

	slog.Info("telemetry initialized",
		"service.name", serviceName(res),
		"sampling_ratio", ratio,
		"traces_endpoint", tracesURL,
		"metrics_endpoint", metricsURL,
		"logs_endpoint", logsURL,
	)

	var once sync.Once
	var shutdownErr error
	return func(ctx context.Context) error {
		once.Do(func() { shutdownErr = shutdownAll(ctx) })
		return shutdownErr
	}, nil
}

// newSampler samples root spans with probability ratio and otherwise follows
// the parent's decision, so traces started upstream are never cut.
func newSampler(ratio float64) sdktrace.Sampler {
	return sdktrace.ParentBased(sdktrace.TraceIDRatioBased(ratio))
}

func serviceName(res *resource.Resource) string {
	v, _ := res.Set().Value(semconv.ServiceNameKey)
	return v.AsString()
}
