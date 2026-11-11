package usecase

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/google/uuid"
)

// ErrFeedLeaseDenied says another trigger holds the user's prefetch lease. It is not
// a failure: inside a transaction it is the sentinel that aborts it cleanly, because
// the duplicate-key error behind it has already aborted the transaction on the server.
var ErrFeedLeaseDenied = errors.New("feed lease is held")

// FeedLease is one holding of a user's prefetch lease. Token identifies it: only its
// token releases it, so a late response cannot release a newer lease.
type FeedLease struct {
	UserID    uuid.UUID
	Owner     string
	Token     uuid.UUID
	ExpiresAt time.Time
}

// FeedLeaseRepository stores the lease. AcquireFeedLease is one atomic round trip and
// reports a held lease as ErrFeedLeaseDenied. Implemented by
// feed/infrastructure/mongo.SessionRepository.
type FeedLeaseRepository interface {
	AcquireFeedLease(ctx context.Context, lease FeedLease, now time.Time) error
	ReleaseFeedLease(ctx context.Context, userID, token uuid.UUID) (bool, error)
}

// FeedLeaseConfig holds the lease's settings (config.FeedConfig). Owner is the id of
// this instance. TTL is the recovery from a crashed holder of an asynchronous request
// and must exceed queue time plus pipeline time. RequestTTL is the same for a holder of
// one synchronous call (AcquireForRequest): it only has to outlive that call, so a pod
// that dies mid-refill does not leave the user's empty feed answering 503 for TTL.
// ReleaseTimeout bounds a release, which outlives its caller.
type FeedLeaseConfig struct {
	Owner          string
	TTL            time.Duration
	RequestTTL     time.Duration
	ReleaseTimeout time.Duration
}

// FeedLeaseManager takes and releases the single per-user lease every feed build
// contends for: the asynchronous triggers and the synchronous refill.
type FeedLeaseManager struct {
	repository FeedLeaseRepository
	config     FeedLeaseConfig
	logger     *slog.Logger
}

// NewFeedLeaseManager creates a FeedLeaseManager.
func NewFeedLeaseManager(repository FeedLeaseRepository, config FeedLeaseConfig, logger *slog.Logger) *FeedLeaseManager {
	return &FeedLeaseManager{repository: repository, config: config, logger: logger}
}

// Acquire takes the lease with a new token, or returns ErrFeedLeaseDenied. It logs
// nothing, because inside a transaction the outcome is only known after the commit
// and the driver may re-run it: RequestFeedPrefetchUsecase logs for it.
func (m *FeedLeaseManager) Acquire(ctx context.Context, userID uuid.UUID) (FeedLease, error) {
	return m.acquire(ctx, userID, m.config.TTL)
}

func (m *FeedLeaseManager) acquire(ctx context.Context, userID uuid.UUID, ttl time.Duration) (FeedLease, error) {
	if userID == uuid.Nil {
		return FeedLease{}, fmt.Errorf("acquire feed lease: user id is required")
	}

	now := time.Now().UTC()
	lease := FeedLease{UserID: userID, Owner: m.config.Owner, Token: uuid.New(), ExpiresAt: now.Add(ttl)}
	if err := m.repository.AcquireFeedLease(ctx, lease, now); err != nil {
		return FeedLease{}, err
	}

	return lease, nil
}

// AcquireForRequest takes the lease outside a transaction, for RequestTTL, for a caller
// that holds it for the duration of a call and then calls Release. A denied lease
// reports false and no error.
func (m *FeedLeaseManager) AcquireForRequest(ctx context.Context, userID uuid.UUID, correlationID string) (FeedLease, bool, error) {
	lease, err := m.acquire(ctx, userID, m.config.RequestTTL)
	if errors.Is(err, ErrFeedLeaseDenied) {
		m.logger.InfoContext(ctx, "feed lease denied", "correlation_id", correlationID, "userId", userID)
		return FeedLease{}, false, nil
	}
	if err != nil {
		return FeedLease{}, false, fmt.Errorf("acquire feed lease: %w", err)
	}

	m.logger.InfoContext(ctx, "feed lease acquired", "correlation_id", correlationID, "userId", userID, "lease_token", lease.Token)

	return lease, true, nil
}

// Release gives the lease back if token still holds it, and reports whether it did.
// It is detached from the caller's cancellation and bounded by ReleaseTimeout, so a
// cancelled request cannot leave the lease held until its TTL.
func (m *FeedLeaseManager) Release(ctx context.Context, userID, token uuid.UUID, correlationID string) (bool, error) {
	releaseCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), m.config.ReleaseTimeout)
	defer cancel()

	released, err := m.repository.ReleaseFeedLease(releaseCtx, userID, token)
	if err != nil {
		m.logger.ErrorContext(releaseCtx, "feed lease release failed", "correlation_id", correlationID, "userId", userID, "lease_token", token, "error", err)
		return false, fmt.Errorf("release feed lease: %w", err)
	}

	if released {
		m.logger.InfoContext(releaseCtx, "feed lease released", "correlation_id", correlationID, "userId", userID, "lease_token", token)
	} else {
		m.logger.InfoContext(releaseCtx, "feed lease not released, the token no longer holds it", "correlation_id", correlationID, "userId", userID, "lease_token", token)
	}

	return released, nil
}
