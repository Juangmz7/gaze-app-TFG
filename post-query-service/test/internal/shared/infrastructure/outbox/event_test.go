package outbox_test

import (
	"context"
	"strings"
	"testing"

	"github.com/google/uuid"
	"go.opentelemetry.io/otel"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"

	"github.com/Juangmz7/TFG/common-packages/go-utils/telemetry"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/outbox"
)

func newEvent(t *testing.T) outbox.Event {
	t.Helper()

	event, err := outbox.NewPendingEvent(uuid.New(), uuid.New(), "TestEvent", "x.test", "rk.test", map[string]string{"k": "v"})
	if err != nil {
		t.Fatalf("NewPendingEvent() error = %v, want nil", err)
	}

	return event
}

func TestEvent_WithTraceContext_CarriesTheTraceOfTheCaller(t *testing.T) {
	// Arrange
	tp := sdktrace.NewTracerProvider()
	previous := otel.GetTracerProvider()
	otel.SetTracerProvider(tp)
	telemetry.SetupPropagator()
	t.Cleanup(func() {
		otel.SetTracerProvider(previous)
		_ = tp.Shutdown(context.Background())
	})
	ctx, parent := tp.Tracer("test").Start(context.Background(), "use case")
	defer parent.End()

	// Act
	event := newEvent(t).WithTraceContext(ctx)

	// Assert
	traceparent := event.TraceContext["traceparent"]
	if !strings.Contains(traceparent, parent.SpanContext().TraceID().String()) {
		t.Fatalf("traceparent = %q, want it to carry the caller trace id %s", traceparent, parent.SpanContext().TraceID())
	}
}

func TestEvent_WithTraceContext_KeepsTheEventUnchangedWithoutASpan(t *testing.T) {
	// Arrange
	telemetry.SetupPropagator()

	// Act
	event := newEvent(t).WithTraceContext(context.Background())

	// Assert
	if event.TraceContext != nil {
		t.Fatalf("TraceContext = %v, want nil when ctx has no span", event.TraceContext)
	}
}

func TestEvent_WithTraceContext_KeepsATraceContextTheEventAlreadyHas(t *testing.T) {
	// Arrange
	telemetry.SetupPropagator()
	event := newEvent(t)
	event.TraceContext = map[string]string{"traceparent": "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"}

	// Act
	got := event.WithTraceContext(context.Background())

	// Assert
	if got.TraceContext["traceparent"] != event.TraceContext["traceparent"] {
		t.Fatalf("traceparent = %q, want the one the event already had", got.TraceContext["traceparent"])
	}
}
