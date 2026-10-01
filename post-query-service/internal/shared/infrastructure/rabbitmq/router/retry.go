package router

import (
	"time"

	"github.com/ThreeDotsLabs/watermill"
	"github.com/ThreeDotsLabs/watermill/message/router/middleware"

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
