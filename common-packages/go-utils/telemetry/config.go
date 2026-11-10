package telemetry

import (
	"fmt"
	"io"
	"log/slog"
	"math"
	"net/url"
	"os"
	"strconv"
	"strings"
)

// Standard OpenTelemetry environment variables honoured by this package.
// See https://opentelemetry.io/docs/specs/otel/configuration/sdk-environment-variables/
const (
	EnvServiceName        = "OTEL_SERVICE_NAME"
	EnvResourceAttributes = "OTEL_RESOURCE_ATTRIBUTES"

	EnvExporterEndpoint        = "OTEL_EXPORTER_OTLP_ENDPOINT"
	EnvExporterTracesEndpoint  = "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"
	EnvExporterMetricsEndpoint = "OTEL_EXPORTER_OTLP_METRICS_ENDPOINT"
	EnvExporterLogsEndpoint    = "OTEL_EXPORTER_OTLP_LOGS_ENDPOINT"

	EnvTracesExporter  = "OTEL_TRACES_EXPORTER"
	EnvMetricsExporter = "OTEL_METRICS_EXPORTER"
	EnvLogsExporter    = "OTEL_LOGS_EXPORTER"

	EnvTracesSamplerArg = "OTEL_TRACES_SAMPLER_ARG"
)

const (
	// DefaultEndpoint is used when neither Config.Endpoint nor any
	// OTEL_EXPORTER_OTLP_*ENDPOINT variable is set: a plaintext local collector.
	DefaultEndpoint = "http://localhost:4317"

	// DefaultSamplingRatio is used when neither Config.SamplingRatio nor
	// OTEL_TRACES_SAMPLER_ARG is set.
	DefaultSamplingRatio = 1.0
)

// Config configures Init. Every field is optional: zero values fall back to
// the standard OTEL_* environment variables, then to the package defaults.
// An explicit (non-zero) field always wins over its environment variable.
type Config struct {
	// ServiceName sets service.name. Falls back to OTEL_SERVICE_NAME, then to
	// service.name in OTEL_RESOURCE_ATTRIBUTES, then to "unknown_service:<exe>".
	ServiceName string

	// ServiceVersion sets service.version. Falls back to
	// OTEL_RESOURCE_ATTRIBUTES.
	ServiceVersion string

	// Environment sets deployment.environment.name (e.g. "dev", "prod").
	// Falls back to deployment.environment.name in OTEL_RESOURCE_ATTRIBUTES.
	Environment string

	// Endpoint is the OTLP gRPC collector endpoint shared by traces, metrics
	// and logs. Accepted forms:
	//   - URL:       "http://collector:4317" (plaintext) or "https://collector:4317" (TLS)
	//   - host:port: "collector:4317" (always plaintext)
	// Falls back, per signal, to OTEL_EXPORTER_OTLP_{TRACES,METRICS,LOGS}_ENDPOINT,
	// then OTEL_EXPORTER_OTLP_ENDPOINT, then DefaultEndpoint.
	Endpoint string

	// SamplingRatio is the TraceIDRatioBased ratio applied to root spans
	// (spans with a parent follow the parent's decision). Zero means unset:
	// falls back to OTEL_TRACES_SAMPLER_ARG, then DefaultSamplingRatio.
	// Any resolved value outside (0, 1] makes Init return an error.
	SamplingRatio float64

	// LogLevel is the minimum level for both stdout and OTLP logs.
	// Defaults to slog.LevelInfo.
	LogLevel slog.Leveler

	// LogOutput receives the JSON logs. Defaults to os.Stdout.
	LogOutput io.Writer
}

// resolveSamplingRatio returns the effective root sampling ratio. Invalid
// values (outside (0, 1], NaN, unparseable env var) are an error rather than
// being silently replaced, so a misconfigured deployment fails at startup.
func resolveSamplingRatio(configured float64) (float64, error) {
	ratio, source := configured, "Config.SamplingRatio"
	if ratio == 0 {
		raw := strings.TrimSpace(os.Getenv(EnvTracesSamplerArg))
		if raw == "" {
			return DefaultSamplingRatio, nil
		}
		parsed, err := strconv.ParseFloat(raw, 64)
		if err != nil {
			return 0, fmt.Errorf("telemetry: invalid %s %q: %w", EnvTracesSamplerArg, raw, err)
		}
		ratio, source = parsed, EnvTracesSamplerArg
	}
	if math.IsNaN(ratio) || ratio <= 0 || ratio > 1 {
		return 0, fmt.Errorf("telemetry: invalid sampling ratio %v from %s: must be in (0, 1]", ratio, source)
	}
	return ratio, nil
}

// resolveEndpoint returns the collector URL for one signal, following
// Config.Endpoint > signal-specific env var > OTEL_EXPORTER_OTLP_ENDPOINT >
// DefaultEndpoint.
func resolveEndpoint(configured, signalEnvVar string) (string, error) {
	for _, candidate := range []string{configured, os.Getenv(signalEnvVar), os.Getenv(EnvExporterEndpoint)} {
		if strings.TrimSpace(candidate) != "" {
			return normalizeEndpoint(candidate)
		}
	}
	return DefaultEndpoint, nil
}

// normalizeEndpoint turns "host:port" or "scheme://host:port" into a URL
// suitable for the exporters' WithEndpointURL option. A bare host:port is
// treated as plaintext ("http://"), matching the in-cluster collector.
func normalizeEndpoint(raw string) (string, error) {
	endpoint := strings.TrimSpace(raw)
	if !strings.Contains(endpoint, "://") {
		endpoint = "http://" + endpoint
	}
	u, err := url.Parse(endpoint)
	if err != nil {
		return "", fmt.Errorf("telemetry: invalid OTLP endpoint %q: %w", raw, err)
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return "", fmt.Errorf("telemetry: invalid OTLP endpoint %q: scheme must be http or https", raw)
	}
	if u.Host == "" {
		return "", fmt.Errorf("telemetry: invalid OTLP endpoint %q: missing host", raw)
	}
	return u.String(), nil
}

// exporterEnabled reports whether a signal should be exported. Only the
// OTLP exporter is supported; setting the variable to "none" disables the
// signal (useful for local runs and tests without a collector).
func exporterEnabled(envVar string) bool {
	return !strings.EqualFold(strings.TrimSpace(os.Getenv(envVar)), "none")
}
