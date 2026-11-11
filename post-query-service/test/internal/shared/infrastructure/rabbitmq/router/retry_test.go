package router_test

import (
	"bytes"
	"errors"
	"log/slog"
	"strings"
	"testing"

	"github.com/ThreeDotsLabs/watermill"
	"github.com/ThreeDotsLabs/watermill/message"
	"github.com/ThreeDotsLabs/watermill/message/router/middleware"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/router"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/topology"
)

func TestNewRetryMiddleware_MatchesPostCommandServiceBackoffValues(t *testing.T) {
	retry := router.NewRetryMiddleware(watermill.NopLogger{})

	if retry.MaxRetries != 2 {
		t.Fatalf("MaxRetries = %d, want 2", retry.MaxRetries)
	}
	if retry.InitialInterval.Seconds() != 2 {
		t.Fatalf("InitialInterval = %v, want 2s", retry.InitialInterval)
	}
	if retry.Multiplier != 2.0 {
		t.Fatalf("Multiplier = %v, want 2.0", retry.Multiplier)
	}
	if retry.MaxInterval.Seconds() != 100 {
		t.Fatalf("MaxInterval = %v, want 100s", retry.MaxInterval)
	}
}

func TestNewRetryMiddleware_ShouldRetry_ReturnsFalseForAPermanentError(t *testing.T) {
	retry := router.NewRetryMiddleware(watermill.NopLogger{})

	shouldRetry := retry.ShouldRetry(middleware.RetryParams{
		Err: rmqerror.NewPermanent(errors.New("malformed payload")),
	})

	if shouldRetry {
		t.Fatal("ShouldRetry() = true, want false for a permanent error so it goes straight to the DLQ")
	}
}

func TestNewRetryMiddleware_ShouldRetry_ReturnsTrueForATransientError(t *testing.T) {
	retry := router.NewRetryMiddleware(watermill.NopLogger{})

	shouldRetry := retry.ShouldRetry(middleware.RetryParams{
		Err: errors.New("mongodb connection reset"),
	})

	if !shouldRetry {
		t.Fatal("ShouldRetry() = false, want true for a transient error so exponential backoff applies")
	}
}

// The retry and dead-letter lines must say which event failed, not only the attempt.
func TestNewEventRetryMiddleware_NamesTheEventInTheDeadLetterLine(t *testing.T) {
	const eventID = "11111111-1111-4111-8111-111111111111"
	logs := &bytes.Buffer{}
	logger := slog.New(slog.NewTextHandler(logs, nil))
	msg := message.NewMessage("msg-1", []byte(`{"id":"`+eventID+`","correlationId":"not-a-uuid"}`))
	msg.Metadata.Set(topology.RoutingKeyHeader, "rk.post.collab.opened.post-created")

	handler := router.NewEventRetryMiddleware(logger)(func(*message.Message) ([]*message.Message, error) {
		return nil, rmqerror.NewPermanent(errors.New("malformed payload"))
	})
	if _, err := handler(msg); err == nil {
		t.Fatal("handler error was swallowed: the message would be acked instead of dead-lettered")
	}

	for _, want := range []string{"dead-letter", "routing_key=rk.post.collab.opened.post-created", "message_uuid=msg-1", "event_id=" + eventID, "permanent=true", "malformed payload"} {
		if !strings.Contains(logs.String(), want) {
			t.Fatalf("log misses %q: %s", want, logs)
		}
	}
	if strings.Contains(logs.String(), "not-a-uuid") {
		t.Fatalf("a payload value that is not a UUID reached the logs: %s", logs)
	}
}

func TestNewEventRetryMiddleware_LogsNothingForAHandledEvent(t *testing.T) {
	logs := &bytes.Buffer{}
	handler := router.NewEventRetryMiddleware(slog.New(slog.NewTextHandler(logs, nil)))(func(*message.Message) ([]*message.Message, error) {
		return nil, nil
	})

	if _, err := handler(message.NewMessage("msg-1", []byte(`{}`))); err != nil || logs.Len() != 0 {
		t.Fatalf("handler() error = %v, logs = %q; want nil and no log", err, logs)
	}
}
