package router

import (
	"context"
	"errors"
	"log/slog"
	"time"

	"github.com/ThreeDotsLabs/watermill"
	"github.com/ThreeDotsLabs/watermill/message"
	"github.com/ThreeDotsLabs/watermill/message/router/middleware"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/eventlog"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
)

// Retry configuration, matching post-command-service's RabbitMQConfig
// (max 2 retries, 2s initial delay, x2 multiplier, 100s max delay; see
// progress/post-query-service/explore_task41_patterns.md section 1).
const (
	retryMaxRetries      = 2
	retryInitialInterval = 2 * time.Second
	retryMultiplier      = 2.0
	retryMaxInterval     = 100 * time.Second
)

// NewRetryMiddleware returns the exponential-backoff retry middleware shared
// by every RabbitMQ consumer handler. A handler error wrapped with
// rmqerror.Permanent (malformed payload, missing envelope fields) skips
// retries entirely: ShouldRetry returns false and the message is nacked
// straight to its dead-letter queue instead of wasting retry attempts on
// data that can never become valid.
func NewRetryMiddleware(logger watermill.LoggerAdapter) middleware.Retry {
	return middleware.Retry{
		MaxRetries:      retryMaxRetries,
		InitialInterval: retryInitialInterval,
		MaxInterval:     retryMaxInterval,
		Multiplier:      retryMultiplier,
		Logger:          logger,
		ShouldRetry: func(params middleware.RetryParams) bool {
			return !rmqerror.IsPermanent(params.Err)
		},
	}
}

// NewEventRetryMiddleware is NewRetryMiddleware with every line naming the event:
// watermill's "Error occurred, retrying" carries only the attempt, and its "Handler
// returned error" only its own message id. Each retry line, and one last line when the
// message goes to its dead-letter queue, carry the routing key and the event's ids.
func NewEventRetryMiddleware(logger *slog.Logger) message.HandlerMiddleware {
	return func(handler message.HandlerFunc) message.HandlerFunc {
		return func(msg *message.Message) ([]*message.Message, error) {
			eventLogger := logger.With(eventlog.MessageAttributes(msg)...)
			produced, err := NewRetryMiddleware(watermill.NewSlogLogger(eventLogger)).Middleware(handler)(msg)
			// A cancelled context is a shutdown, not a failure of the event.
			if err != nil && !errors.Is(err, context.Canceled) {
				eventLogger.ErrorContext(msg.Context(), "event failed, nacking it to its dead-letter queue",
					"error", err, "permanent", rmqerror.IsPermanent(err))
			}
			return produced, err
		}
	}
}
