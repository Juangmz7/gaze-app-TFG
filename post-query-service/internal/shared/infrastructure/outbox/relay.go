package outbox

import (
	"context"
	"fmt"
	"log/slog"
	"time"

	"github.com/google/uuid"
)

// maxErrorLength bounds the last_error stored on an event.
const maxErrorLength = 1000

// RelayStore is the subset of Store the relay depends on.
type RelayStore interface {
	ClaimNext(ctx context.Context, lockTimeout time.Duration) (*Event, error)
	MarkProcessed(ctx context.Context, id uuid.UUID) error
	MarkFailedAttempt(ctx context.Context, id uuid.UUID, status Status, lastError string) error
}

// Publisher publishes one event and returns nil only once the broker has
// confirmed it.
type Publisher interface {
	// Ready reports whether the broker is reachable.
	Ready(ctx context.Context) error
	Publish(ctx context.Context, event Event) error
}

// RelayConfig tunes the relay loop.
type RelayConfig struct {
	// Interval is the pause between batches.
	Interval time.Duration
	// BatchSize caps how many events one batch claims.
	BatchSize int
	// MaxAttempts moves an event to FAILED once reached.
	MaxAttempts int
	// LockTimeout is how long a PROCESSING claim is honored before another
	// relay may reclaim it. It must exceed the publisher's confirm timeout.
	LockTimeout time.Duration
}

// DefaultRelayConfig returns the settings used in production.
func DefaultRelayConfig() RelayConfig {
	return RelayConfig{
		Interval:    5 * time.Second,
		BatchSize:   100,
		MaxAttempts: 10,
		LockTimeout: 60 * time.Second,
	}
}

// Relay moves outbox events to RabbitMQ: claim, publish with confirm, mark.
type Relay struct {
	store     RelayStore
	publisher Publisher
	cfg       RelayConfig
	logger    *slog.Logger
}

// NewRelay creates a Relay.
func NewRelay(store RelayStore, publisher Publisher, cfg RelayConfig, logger *slog.Logger) *Relay {
	return &Relay{store: store, publisher: publisher, cfg: cfg, logger: logger}
}

// Start runs the relay loop in its own goroutine until ctx is cancelled. The
// returned channel is closed once the loop has exited.
func (r *Relay) Start(ctx context.Context) <-chan struct{} {
	done := make(chan struct{})
	go func() {
		defer close(done)

		ticker := time.NewTicker(r.cfg.Interval)
		defer ticker.Stop()

		for {
			if err := r.RelayBatch(ctx); err != nil && ctx.Err() == nil {
				r.logger.Error("outbox relay iteration failed", "error", err)
			}

			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
			}
		}
	}()
	return done
}

// RelayBatch publishes up to BatchSize events, oldest first. It stops at the
// first failed publish so later events are not sent ahead of it.
func (r *Relay) RelayBatch(ctx context.Context) error {
	// Claiming consumes an attempt, so nothing is claimed while the broker is
	// unreachable: an outage longer than MaxAttempts batches must not move
	// events to FAILED.
	if err := r.publisher.Ready(ctx); err != nil {
		r.logger.Warn("outbox relay skipped, broker unavailable", "error", err)
		return nil
	}

	for i := 0; i < r.cfg.BatchSize; i++ {
		if ctx.Err() != nil {
			return nil
		}

		event, err := r.store.ClaimNext(ctx, r.cfg.LockTimeout)
		if err != nil {
			return err
		}
		if event == nil {
			return nil
		}

		// Release/mark even if ctx is cancelled mid-publish (shutdown), so the
		// claim does not have to wait for the lock timeout.
		markCtx := context.WithoutCancel(ctx)

		if publishErr := r.publisher.Publish(ctx, *event); publishErr != nil {
			return r.releaseFailedAttempt(markCtx, event, publishErr)
		}

		if err := r.store.MarkProcessed(markCtx, event.ID); err != nil {
			return err
		}
	}
	return nil
}

func (r *Relay) releaseFailedAttempt(ctx context.Context, event *Event, publishErr error) error {
	status := StatusPending
	if event.Attempts >= r.cfg.MaxAttempts {
		status = StatusFailed
		r.logger.Error("outbox event moved to FAILED",
			"id", event.ID, "type", event.EventType, "attempts", event.Attempts, "error", publishErr)
	} else {
		r.logger.Warn("outbox publish failed",
			"id", event.ID, "type", event.EventType, "attempt", event.Attempts, "error", publishErr)
	}

	lastError := publishErr.Error()
	if len(lastError) > maxErrorLength {
		lastError = lastError[:maxErrorLength]
	}

	if err := r.store.MarkFailedAttempt(ctx, event.ID, status, lastError); err != nil {
		return fmt.Errorf("release outbox event %s after publish error %v: %w", event.ID, publishErr, err)
	}
	return nil
}
