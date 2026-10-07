package bootstrap

import (
	"context"
	"log/slog"
	"time"

	"go.mongodb.org/mongo-driver/v2/mongo"
)

// Run runs the Watermill router until ctx is cancelled, Close is called, or
// a fatal routing error occurs.
func (r Result) Run(ctx context.Context) error {
	return r.Router.Run(ctx)
}

// RunnerCloser is the subset of Result's behavior the retry loop in
// Supervise depends on. Result satisfies it already via Run and Close.
//
// This is exported, instead of the package-private interface a Watermill
// AMQP-free unit test would normally be able to reach from a co-located
// _test.go file, because this service's testing convention
// (docs/conventions/post-query-service-conventions.md) mirrors every
// package under a top-level test/ tree and forbids co-located test files —
// a black-box test/.../bootstrap package can only exercise identifiers this
// package exports. Supervise and RunnerCloser are the seam that lets the
// retry loop be unit-tested with a fake bootstrap function and a fake
// RunnerCloser, without a live AMQP broker.
type RunnerCloser interface {
	// Run runs until ctx is cancelled or a fatal error occurs.
	Run(ctx context.Context) error
	// Close releases the resources opened for this run.
	Close() error
}

const (
	// supervisorBackoffInitial is the delay before the first retry attempt.
	supervisorBackoffInitial = 2 * time.Second
	// supervisorBackoffStep is the linear increase applied per attempt.
	supervisorBackoffStep = 3 * time.Second
	// supervisorBackoffMax caps the delay regardless of attempt count. It is
	// deliberately flatter than the per-message retry cap in
	// rabbitmq/router.NewRetryMiddleware (2s/x2/100s): that backoff protects
	// a single message's redelivery, while this one governs how quickly the
	// service tries to re-establish a lost AMQP connection, which should
	// stay closer to linear and recover sooner.
	supervisorBackoffMax = 30 * time.Second
)

// supervisorBackoff returns the delay before retry attempt n (1-indexed):
// 2s, 5s, 8s, 11s, ... capped at supervisorBackoffMax.
func supervisorBackoff(attempt int) time.Duration {
	if attempt < 1 {
		attempt = 1
	}
	d := supervisorBackoffInitial + time.Duration(attempt-1)*supervisorBackoffStep
	if d > supervisorBackoffMax {
		return supervisorBackoffMax
	}
	return d
}

// RunWithRetry runs Bootstrap and the resulting router in a loop that
// survives AMQP connection loss. It delegates to Supervise so the retry
// control flow has a single implementation; see Supervise's doc comment for
// the full behavior.
//
// RunWithRetry returns immediately. The loop runs in its own goroutine and
// stops when ctx is cancelled; the returned channel is closed once the loop
// has exited and the last attempt's resources are fully closed, so callers
// can block on it during shutdown to avoid racing cleanup.
func RunWithRetry(ctx context.Context, amqpURI string, db *mongo.Database, logger *slog.Logger) <-chan struct{} {
	bootstrapFn := func(ctx context.Context) (RunnerCloser, error) {
		return Bootstrap(ctx, amqpURI, db, logger)
	}
	return Supervise(ctx, bootstrapFn, logger)
}

// Supervise runs bootstrap and the resulting RunnerCloser in a loop that
// survives AMQP connection loss. Every attempt tears down and rebuilds
// every subscriber/publisher/router from scratch via bootstrap — reusing a
// RunnerCloser after Run returns is not safe, since its underlying AMQP
// channels are already dead. Between attempts it waits with linear backoff
// (supervisorBackoff), reset to the initial delay immediately after any
// bootstrap call that succeeds (regardless of how long the resulting Run
// lasted).
//
// Supervise returns immediately. The loop runs in its own goroutine and
// stops when ctx is cancelled; the returned channel is closed once the loop
// has exited and the last attempt's resources are fully closed, so callers
// can block on it during shutdown to avoid racing cleanup.
func Supervise(ctx context.Context, bootstrap func(context.Context) (RunnerCloser, error), logger *slog.Logger) <-chan struct{} {
	done := make(chan struct{})
	go func() {
		defer close(done)
		attempt := 0
		for {
			if ctx.Err() != nil {
				return
			}

			rc, err := bootstrap(ctx)
			if err != nil {
				if ctx.Err() != nil {
					return
				}
				attempt++
				delay := supervisorBackoff(attempt)
				logger.Error("rabbitmq bootstrap failed, retrying", "error", err, "attempt", attempt, "backoff", delay)
				if !sleepOrDone(ctx, delay) {
					return
				}
				continue
			}
			attempt = 0

			runErr := rc.Run(ctx)
			if closeErr := rc.Close(); closeErr != nil {
				logger.Error("rabbitmq cleanup failed", "error", closeErr)
			}
			if ctx.Err() != nil {
				return
			}

			attempt++
			delay := supervisorBackoff(attempt)
			if runErr != nil {
				logger.Error("rabbitmq router stopped, retrying", "error", runErr, "attempt", attempt, "backoff", delay)
			} else {
				logger.Warn("rabbitmq router stopped unexpectedly without error, retrying", "attempt", attempt, "backoff", delay)
			}
			if !sleepOrDone(ctx, delay) {
				return
			}
		}
	}()
	return done
}

// sleepOrDone waits for d or ctx cancellation, whichever comes first. It
// returns false if ctx was cancelled (caller should stop looping) and true
// if the full delay elapsed.
func sleepOrDone(ctx context.Context, d time.Duration) bool {
	select {
	case <-ctx.Done():
		return false
	case <-time.After(d):
		return true
	}
}
