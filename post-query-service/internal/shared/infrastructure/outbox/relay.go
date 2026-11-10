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
	ClaimIfPending(ctx context.Context, id uuid.UUID) (*Event, error)
	MarkProcessed(ctx context.Context, id uuid.UUID) error
	MarkFailedAttempt(ctx context.Context, id uuid.UUID, attempts int, status Status, lastError string) (bool, error)
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
	// Interval is the pause between batches. Events are normally published
	// right after their commit (RelayImmediately); the batch is the safety net.
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
		Interval:    4 * time.Second,
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

		if published, err := r.publishClaimed(ctx, event); !published {
			return err
		}
	}
	return nil
}

// RelayImmediately is the low-latency path: called right after the
// transaction that stored event id commits (see Store.SendImmediatelyWith),
// it publishes that event through the same claim, confirm and mark steps as
// RelayBatch. It is best effort: when the broker is unreachable or the publish
// fails, the event is left to the next batch.
func (r *Relay) RelayImmediately(ctx context.Context, id uuid.UUID) {
	// Same reason as RelayBatch: do not spend an attempt while the broker is down
	if err := r.publisher.Ready(ctx); err != nil {
		return
	}

	event, err := r.store.ClaimIfPending(ctx, id)
	if err != nil {
		r.logger.Warn("outbox immediate send failed to claim the event, left to the relay batch", "id", id, "error", err)
		return
	}
	if event == nil {
		return // a relay batch already took it
	}

	if _, err := r.publishClaimed(ctx, event); err != nil {
		r.logger.Error("outbox immediate send failed", "id", id, "error", err)
	}
}

// publishClaimed publishes a claimed event and records the outcome. It
// returns false when the event was not marked PROCESSED, so a batch stops
// there and later events are not sent ahead of it.
func (r *Relay) publishClaimed(ctx context.Context, event *Event) (bool, error) {
	// Release/mark even if ctx is cancelled mid-publish (shutdown), so the
	// claim does not have to wait for the lock timeout.
	markCtx := context.WithoutCancel(ctx)

	if publishErr := r.publisher.Publish(ctx, *event); publishErr != nil {
		return false, r.releaseFailedAttempt(markCtx, event, publishErr)
	}

	if err := r.store.MarkProcessed(markCtx, event.ID); err != nil {
		// Delivered but still PROCESSING: it is reclaimed and sent again once the lock expires
		return false, fmt.Errorf("outbox event %s (type %s, attempt %d) was published but not marked processed; "+
			"it will be republished as a duplicate after the %s lock timeout: %w",
			event.ID, event.EventType, event.Attempts, r.cfg.LockTimeout, err)
	}
	return true, nil
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

	released, err := r.store.MarkFailedAttempt(ctx, event.ID, event.Attempts, status, lastError)
	if err != nil {
		return fmt.Errorf("release outbox event %s after publish error %v: %w", event.ID, publishErr, err)
	}
	if !released {
		r.logger.Warn("outbox claim expired before its failure was recorded, left to the current owner",
			"id", event.ID, "attempt", event.Attempts)
	}
	return nil
}
