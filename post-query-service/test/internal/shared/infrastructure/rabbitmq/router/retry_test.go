package router_test

import (
	"errors"
	"testing"

	"github.com/ThreeDotsLabs/watermill"
	"github.com/ThreeDotsLabs/watermill/message/router/middleware"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/router"
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
