package telemetry

import (
	"context"
	"errors"
	"fmt"
	"log/slog"

	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/sdk/resource"
	semconv "go.opentelemetry.io/otel/semconv/v1.41.0"
)

// newResource builds the resource shared by all three providers.
//
// Detectors run in order and later ones override earlier ones, so the
// precedence is: explicit Config fields > OTEL_SERVICE_NAME /
// OTEL_RESOURCE_ATTRIBUTES > detected defaults. semconv v1.41.0 is the same
// version the SDK's own detectors use (OTel Go v1.44.0), so no schema URL
// conflict can arise.
func newResource(ctx context.Context, cfg Config) (*resource.Resource, error) {
	var explicit []attribute.KeyValue
	if cfg.ServiceName != "" {
		explicit = append(explicit, semconv.ServiceName(cfg.ServiceName))
	}
	if cfg.ServiceVersion != "" {
		explicit = append(explicit, semconv.ServiceVersion(cfg.ServiceVersion))
	}
	if cfg.Environment != "" {
		explicit = append(explicit, semconv.DeploymentEnvironmentNameKey.String(cfg.Environment))
	}

	res, err := resource.New(ctx,
		resource.WithSchemaURL(semconv.SchemaURL),
		// service.instance.id and the "unknown_service:<exe>" fallback name.
		resource.WithService(),
		resource.WithTelemetrySDK(),
		resource.WithHost(),
		resource.WithContainer(),
		// Process attributes, deliberately excluding command args and owner
		// (args can carry secrets).
		resource.WithProcessPID(),
		resource.WithProcessExecutableName(),
		resource.WithProcessRuntimeName(),
		resource.WithProcessRuntimeVersion(),
		resource.WithProcessRuntimeDescription(),
		resource.WithFromEnv(),
		resource.WithAttributes(explicit...),
	)
	switch {
	case err == nil:
		return res, nil
	case errors.Is(err, resource.ErrPartialResource) && !errors.Is(err, resource.ErrSchemaURLConflict):
		// A detector failed (e.g. malformed OTEL_RESOURCE_ATTRIBUTES); keep
		// whatever was detected instead of refusing to start.
		slog.Warn("telemetry: resource partially detected", "error", err)
		return res, nil
	default:
		return nil, fmt.Errorf("telemetry: create resource: %w", err)
	}
}
