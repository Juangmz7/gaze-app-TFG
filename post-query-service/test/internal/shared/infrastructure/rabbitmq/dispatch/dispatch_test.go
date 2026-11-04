package dispatch_test

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"testing"

	"github.com/ThreeDotsLabs/watermill/message"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/dispatch"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/topology"
)

type ctxKey struct{}

func TestDispatcher_Handle_CallsTheHandlerRegisteredForTheRoutingKey(t *testing.T) {
	called := false
	var gotCtx context.Context

	handlers := map[string]dispatch.EventHandlerFunc{
		"rk.post.created": func(ctx context.Context, msg *message.Message) error {
			called = true
			gotCtx = ctx
			return nil
		},
	}
	dispatcher := dispatch.New(topology.QueuePost, handlers, testLogger())

	ctx := context.WithValue(context.Background(), ctxKey{}, "expected")
	msg := message.NewMessage("1", []byte(`{}`))
	msg.Metadata.Set(topology.RoutingKeyHeader, "rk.post.created")
	msg.SetContext(ctx)

	if err := dispatcher.Handle(msg); err != nil {
		t.Fatalf("Handle() error = %v, want nil", err)
	}

	if !called {
		t.Fatal("Handle() did not call the handler registered for the routing key")
	}
	if gotCtx.Value(ctxKey{}) != "expected" {
		t.Fatal("Handle() did not propagate the message context to the handler")
	}
}

func TestDispatcher_Handle_ReturnsTheHandlerError(t *testing.T) {
	wantErr := errors.New("usecase failed")
	handlers := map[string]dispatch.EventHandlerFunc{
		"rk.post.created": func(ctx context.Context, msg *message.Message) error {
			return wantErr
		},
	}
	dispatcher := dispatch.New(topology.QueuePost, handlers, testLogger())

	msg := message.NewMessage("1", []byte(`{}`))
	msg.Metadata.Set(topology.RoutingKeyHeader, "rk.post.created")

	if err := dispatcher.Handle(msg); !errors.Is(err, wantErr) {
		t.Fatalf("Handle() error = %v, want %v", err, wantErr)
	}
}

func TestDispatcher_Handle_AcksAndSkipsWhenNoHandlerIsRegisteredForTheRoutingKey(t *testing.T) {
	called := false
	handlers := map[string]dispatch.EventHandlerFunc{
		"rk.post.created": func(ctx context.Context, msg *message.Message) error {
			called = true
			return nil
		},
	}
	dispatcher := dispatch.New(topology.QueuePost, handlers, testLogger())

	msg := message.NewMessage("1", []byte(`{}`))
	msg.Metadata.Set(topology.RoutingKeyHeader, topology.RKPostRecommendedSent)

	if err := dispatcher.Handle(msg); err != nil {
		t.Fatalf("Handle() error = %v, want nil for an unregistered routing key", err)
	}
	if called {
		t.Fatal("Handle() called a handler registered for a different routing key")
	}
}

func testLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}
