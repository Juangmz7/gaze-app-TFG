package router_test

import (
	"context"
	"errors"
	"testing"

	"github.com/ThreeDotsLabs/watermill"
	"github.com/ThreeDotsLabs/watermill/message"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/codes"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/sdk/trace/tracetest"
	"go.opentelemetry.io/otel/trace"

	"github.com/Juangmz7/TFG/common-packages/go-utils/telemetry"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/router"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/topology"
)

// installRecorder swaps the global TracerProvider for one that records every
// span, and restores the previous provider when the test ends.
func installRecorder(t *testing.T) (*tracetest.SpanRecorder, *sdktrace.TracerProvider) {
	t.Helper()
	recorder := tracetest.NewSpanRecorder()
	tp := sdktrace.NewTracerProvider(sdktrace.WithSpanProcessor(recorder))
	previous := otel.GetTracerProvider()
	otel.SetTracerProvider(tp)
	telemetry.SetupPropagator()
	t.Cleanup(func() {
		otel.SetTracerProvider(previous)
		_ = tp.Shutdown(context.Background())
	})
	return recorder, tp
}

// newUpstreamMessage builds a message whose metadata carries the trace context
// of an upstream (publisher) span, as the AMQP marshaler would deliver it.
func newUpstreamMessage(t *testing.T, tp *sdktrace.TracerProvider) (*message.Message, trace.SpanContext) {
	t.Helper()
	ctx, upstream := tp.Tracer("upstream").Start(context.Background(), "send x.post.events")
	upstream.End()

	msg := message.NewMessage(watermill.NewUUID(), []byte(`{}`))
	msg.Metadata = telemetry.InjectMetadata(ctx, msg.Metadata)
	msg.Metadata.Set(topology.RoutingKeyHeader, topology.RKPostCreated)
	return msg, upstream.SpanContext()
}

func TestTracingMiddleware_ContinuesTheUpstreamTraceInAConsumerSpan(t *testing.T) {
	recorder, tp := installRecorder(t)
	msg, upstream := newUpstreamMessage(t, tp)

	var handlerSpan trace.SpanContext
	handler := router.TracingMiddleware(func(m *message.Message) ([]*message.Message, error) {
		handlerSpan = trace.SpanContextFromContext(m.Context())
		return nil, nil
	})

	if _, err := handler(msg); err != nil {
		t.Fatalf("handler() error = %v, want nil", err)
	}

	var consumer sdktrace.ReadOnlySpan
	for _, s := range recorder.Ended() {
		if s.SpanKind() == trace.SpanKindConsumer {
			consumer = s
		}
	}
	if consumer == nil {
		t.Fatalf("no CONSUMER span recorded; got %d spans", len(recorder.Ended()))
	}
	if consumer.SpanContext().TraceID() != upstream.TraceID() {
		t.Fatalf("consumer trace id = %s, want upstream %s", consumer.SpanContext().TraceID(), upstream.TraceID())
	}
	if consumer.Parent().SpanID() != upstream.SpanID() {
		t.Fatalf("consumer parent span id = %s, want upstream %s", consumer.Parent().SpanID(), upstream.SpanID())
	}
	if handlerSpan.SpanID() != consumer.SpanContext().SpanID() {
		t.Fatalf("handler context span id = %s, want consumer span %s", handlerSpan.SpanID(), consumer.SpanContext().SpanID())
	}

	foundRoutingKey := false
	for _, attr := range consumer.Attributes() {
		if string(attr.Key) == "messaging.rabbitmq.destination.routing_key" && attr.Value.AsString() == topology.RKPostCreated {
			foundRoutingKey = true
		}
	}
	if !foundRoutingKey {
		t.Fatalf("consumer span attributes %v lack routing key %q", consumer.Attributes(), topology.RKPostCreated)
	}
}

func TestTracingMiddleware_MarksTheSpanAsErrorWhenTheHandlerFails(t *testing.T) {
	recorder, tp := installRecorder(t)
	msg, _ := newUpstreamMessage(t, tp)

	wantErr := errors.New("mongo unavailable")
	handler := router.TracingMiddleware(func(*message.Message) ([]*message.Message, error) {
		return nil, wantErr
	})

	if _, err := handler(msg); !errors.Is(err, wantErr) {
		t.Fatalf("handler() error = %v, want %v", err, wantErr)
	}

	for _, s := range recorder.Ended() {
		if s.SpanKind() == trace.SpanKindConsumer {
			if s.Status().Code != codes.Error {
				t.Fatalf("consumer span status = %v, want Error", s.Status().Code)
			}
			return
		}
	}
	t.Fatal("no CONSUMER span recorded")
}
