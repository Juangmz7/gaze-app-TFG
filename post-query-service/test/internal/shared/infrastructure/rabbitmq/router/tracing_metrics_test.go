package router_test

import (
	"context"
	"errors"
	"testing"

	"github.com/ThreeDotsLabs/watermill"
	"github.com/ThreeDotsLabs/watermill/message"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/metric/metricdata"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/router"
)

// installMetricReader swaps the global MeterProvider for one read on demand, and
// restores the previous provider when the test ends.
func installMetricReader(t *testing.T) *sdkmetric.ManualReader {
	t.Helper()
	reader := sdkmetric.NewManualReader()
	provider := sdkmetric.NewMeterProvider(sdkmetric.WithReader(reader))
	previous := otel.GetMeterProvider()
	otel.SetMeterProvider(provider)
	t.Cleanup(func() {
		otel.SetMeterProvider(previous)
		_ = provider.Shutdown(context.Background())
	})

	return reader
}

// processDurationPoints returns the data points of messaging.process.duration.
func processDurationPoints(t *testing.T, reader *sdkmetric.ManualReader) []metricdata.HistogramDataPoint[float64] {
	t.Helper()
	var collected metricdata.ResourceMetrics
	if err := reader.Collect(context.Background(), &collected); err != nil {
		t.Fatalf("Collect() error = %v, want nil", err)
	}
	for _, scope := range collected.ScopeMetrics {
		for _, m := range scope.Metrics {
			if m.Name != "messaging.process.duration" {
				continue
			}
			histogram, ok := m.Data.(metricdata.Histogram[float64])
			if !ok {
				t.Fatalf("messaging.process.duration data = %T, want a float64 histogram", m.Data)
			}

			return histogram.DataPoints
		}
	}

	return nil
}

func TestTracingMiddleware_RecordsTheProcessDurationOfAHandledMessage(t *testing.T) {
	// Arrange
	reader := installMetricReader(t)
	handler := router.TracingMiddleware(func(*message.Message) ([]*message.Message, error) {
		return nil, nil
	})

	// Act
	if _, err := handler(message.NewMessage(watermill.NewUUID(), []byte(`{}`))); err != nil {
		t.Fatalf("handler() error = %v, want nil", err)
	}

	// Assert
	points := processDurationPoints(t, reader)
	if len(points) != 1 || points[0].Count != 1 {
		t.Fatalf("messaging.process.duration points = %+v, want one point with one measurement", points)
	}
	if _, failed := points[0].Attributes.Value(attribute.Key("error.type")); failed {
		t.Fatal("error.type is set on a message that was handled without error")
	}
}

func TestTracingMiddleware_MarksTheProcessDurationOfAFailedMessageWithAnErrorType(t *testing.T) {
	// Arrange
	reader := installMetricReader(t)
	handler := router.TracingMiddleware(func(*message.Message) ([]*message.Message, error) {
		return nil, errors.New("handler failed")
	})

	// Act
	if _, err := handler(message.NewMessage(watermill.NewUUID(), []byte(`{}`))); err == nil {
		t.Fatal("handler() error = nil, want the handler error")
	}

	// Assert
	points := processDurationPoints(t, reader)
	if len(points) != 1 {
		t.Fatalf("messaging.process.duration points = %+v, want one point", points)
	}
	errorType, failed := points[0].Attributes.Value(attribute.Key("error.type"))
	if !failed || errorType.AsString() != "handler_error" {
		t.Fatalf("error.type = %q (set: %v), want handler_error", errorType.AsString(), failed)
	}
}
