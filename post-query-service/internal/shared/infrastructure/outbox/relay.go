package outbox

import (
	"context"
	"fmt"
	"log/slog"
	"sync"
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
	// right after their commit, in the background (RelayImmediately); the batch
	// is the safety net.
	Interval time.Duration
	// BatchSize caps how many events one batch claims.
	BatchSize int
	// MaxAttempts moves an event to FAILED once reached.
	MaxAttempts int
	// LockTimeout is how long a PROCESSING claim is honored before another
	// relay may reclaim it. It must exceed the publisher's confirm timeout.
	LockTimeout time.Duration
	// ImmediateConcurrency bounds the immediate sends in flight. When they are all
	// busy (a slow broker) an event is simply left to the next batch.
	ImmediateConcurrency int
	// ImmediateTimeout bounds one immediate send, which outlives its caller.
	ImmediateTimeout time.Duration
	// ShutdownWait bounds how long Wait waits for the immediate sends in flight.
	// Whatever does not finish in time is recovered by a batch.
	ShutdownWait time.Duration
}

// DefaultRelayConfig returns the settings used in production.
func DefaultRelayConfig() RelayConfig {
	return RelayConfig{
		Interval:    4 * time.Second,
		BatchSize:   100,
		MaxAttempts: 10,
		LockTimeout: 60 * time.Second,
		// Publishes are serialized by AMQPPublisher, so more than a few in flight
		// would only queue up behind a slow broker.
		ImmediateConcurrency: 8,
		ImmediateTimeout:     20 * time.Second,
		ShutdownWait:         15 * time.Second,
	}
}

// Relay moves outbox events to RabbitMQ: claim, publish with confirm, mark.
type Relay struct {
	store     RelayStore
	publisher Publisher
	cfg       RelayConfig
	logger    *slog.Logger

	// mu orders stopping against immediateInFlight.Add, so Wait cannot miss a send.
	mu                sync.Mutex
	stopping          bool
	immediateSlots    chan struct{}
	immediateInFlight sync.WaitGroup
}

// NewRelay creates a Relay. Call Wait on shutdown, after whatever stores events has
// stopped and before closing the publisher.
func NewRelay(store RelayStore, publisher Publisher, cfg RelayConfig, logger *slog.Logger) *Relay {
	defaults := DefaultRelayConfig()
	if cfg.ImmediateConcurrency <= 0 {
		cfg.ImmediateConcurrency = defaults.ImmediateConcurrency
	}
	if cfg.ImmediateTimeout <= 0 {
		cfg.ImmediateTimeout = defaults.ImmediateTimeout
	}
	if cfg.ShutdownWait <= 0 {
		cfg.ShutdownWait = defaults.ShutdownWait
	}

	return &Relay{
		store:          store,
		publisher:      publisher,
		cfg:            cfg,
		logger:         logger,
		immediateSlots: make(chan struct{}, cfg.ImmediateConcurrency),
	}
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

// RelayImmediately is the low-latency path: called right after the transaction
// that stored event id commits (see Store.SendImmediatelyWith), it starts the
// publish in the background and returns at once, so a slow broker never holds up
// the caller, who may be answering an HTTP request. The send is detached from the
// caller's cancellation and bounded by ImmediateTimeout. It is best effort: with
// every slot busy, while stopping, when the broker is unreachable or when the
// publish fails, the event is left to the next batch.
func (r *Relay) RelayImmediately(ctx context.Context, id uuid.UUID) {
	if !r.reserveImmediateSlot(id) {
		return
	}

	sendCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), r.cfg.ImmediateTimeout)
	go func() {
		defer func() {
			cancel()
			<-r.immediateSlots
			r.immediateInFlight.Done()
		}()
		r.sendImmediately(sendCtx, id)
	}()
}

// Wait stops new immediate sends and waits for those in flight, up to ShutdownWait.
func (r *Relay) Wait() {
	r.mu.Lock()
	r.stopping = true
	r.mu.Unlock()

	done := make(chan struct{})
	go func() {
		r.immediateInFlight.Wait()
		close(done)
	}()

	timer := time.NewTimer(r.cfg.ShutdownWait)
	defer timer.Stop()
	select {
	case <-done:
	case <-timer.C:
		r.logger.Warn("outbox immediate sends still running at shutdown, left to a relay batch", "waited", r.cfg.ShutdownWait)
	}
}

func (r *Relay) reserveImmediateSlot(id uuid.UUID) bool {
	r.mu.Lock()
	defer r.mu.Unlock()

	if r.stopping {
		return false
	}
	select {
	case r.immediateSlots <- struct{}{}:
		r.immediateInFlight.Add(1)
		return true
	default:
		r.logger.Info("outbox immediate sends all busy, event left to the relay batch", "event_id", id)
		return false
	}
}

// sendImmediately publishes one event through the same claim, confirm and mark
// steps as RelayBatch.
func (r *Relay) sendImmediately(ctx context.Context, id uuid.UUID) {
	// Same reason as RelayBatch: do not spend an attempt while the broker is down
	if err := r.publisher.Ready(ctx); err != nil {
		return
	}

	event, err := r.store.ClaimIfPending(ctx, id)
	if err != nil {
		r.logger.Warn("outbox immediate send failed to claim the event, left to the relay batch", "event_id", id, "error", err)
		return
	}
	if event == nil {
		return // a relay batch already took it
	}

	if _, err := r.publishClaimed(ctx, event); err != nil {
		r.logger.Error("outbox immediate send failed", "event_id", id, "error", err)
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
			"event_id", event.ID, "event_type", event.EventType, "routing_key", event.RoutingKey, "attempts", event.Attempts, "error", publishErr)
	} else {
		r.logger.Warn("outbox publish failed",
			"event_id", event.ID, "event_type", event.EventType, "routing_key", event.RoutingKey, "attempt", event.Attempts, "error", publishErr)
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
			"event_id", event.ID, "event_type", event.EventType, "attempt", event.Attempts)
	}
	return nil
}
