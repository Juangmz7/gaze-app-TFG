package usecase

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/google/uuid"
)

// ErrFeedTemporarilyUnavailable says nothing can be served right now, while the feed
// has not ended. Match it with errors.Is; FeedTemporarilyUnavailableError has the wait.
var ErrFeedTemporarilyUnavailable = errors.New("feed is temporarily unavailable")

// ErrCallerDeadlineEndedRead says a read got no answer because the deadline of the
// caller's context came, or was too close to send the read. It is not a failure of the
// store, and the context may not report it yet: the deadline is on the clock when the
// driver looks at it, and the context's timer has not necessarily run.
var ErrCallerDeadlineEndedRead = errors.New("the caller's deadline ended the read")

// FeedTemporarilyUnavailableError is the degraded outcome of a refill. It is not the
// end of the feed: a client must retry after RetryAfter.
type FeedTemporarilyUnavailableError struct {
	RetryAfter time.Duration
	Cause      error
}

// Error implements error.
func (e *FeedTemporarilyUnavailableError) Error() string {
	return fmt.Sprintf("%v: %v", ErrFeedTemporarilyUnavailable, e.Cause)
}

// Unwrap exposes both the sentinel and the cause.
func (e *FeedTemporarilyUnavailableError) Unwrap() []error {
	return []error{ErrFeedTemporarilyUnavailable, e.Cause}
}

// RecommendationRequest asks recommendation-service for a user's next posts.
type RecommendationRequest struct {
	UserID          uuid.UUID
	ExcludedPostIDs []uuid.UUID
	CorrelationID   string
}

// RecommendationClient calls recommendation-service. An empty list with no error means
// it has no candidates. Implemented by feed/infrastructure/grpc.RecommendationClient.
type RecommendationClient interface {
	GetRecommendedFeed(ctx context.Context, request RecommendationRequest) ([]uuid.UUID, error)
}

// RefillFeedUnitRepository says whether a claim has something to read. A read ended by
// the deadline of ctx wraps ErrCallerDeadlineEndedRead; one that timed out on any shorter
// limit of the repository's own does not, because the store failed to answer in time
// that the caller still had. Implemented by feed/infrastructure/mongo.FeedUnitRepository.
type RefillFeedUnitRepository interface {
	HasAfterPosition(ctx context.Context, userID string, afterPosition *int64, unservedOnly bool) (bool, error)
}

// RefillFeedUnitBuilder stores the recommended posts. Implemented by BuildFeedUnitsUsecase.
type RefillFeedUnitBuilder interface {
	Execute(ctx context.Context, input BuildFeedUnitsInput) (BuildFeedUnitsOutput, error)
}

// RefillFeedConfig holds the refill's settings (config.FeedConfig).
type RefillFeedConfig struct {
	// LeaseWait is how long a claim polls the stored feed while another build holds
	// the lease.
	LeaseWait time.Duration
	// PollInterval is the pause between those reads.
	PollInterval time.Duration
	// RetryAfter is what a degraded response tells the client to wait.
	RetryAfter time.Duration
}

// RefillFeedInput identifies the claim that found nothing and where it reads from.
type RefillFeedInput struct {
	UserID        uuid.UUID
	AfterPosition *int64
	UnservedOnly  bool
	CorrelationID string
}

// RefillFeedStatus says what a claim can do after a refill that did not fail.
type RefillFeedStatus string

const (
	// RefillFeedStatusUnitsAvailable means the claim has units to read now.
	RefillFeedStatusUnitsAvailable RefillFeedStatus = "units_available"
	// RefillFeedStatusNoPosts means recommendation-service answered with no posts: the
	// only case in which a claim may tell a client that the feed ended.
	RefillFeedStatusNoPosts RefillFeedStatus = "no_posts"
)

// RefillFeedUsecase builds a user's feed while they wait, when a claim found nothing.
type RefillFeedUsecase struct {
	units   RefillFeedUnitRepository
	lease   *FeedLeaseManager
	backlog *FeedBacklog
	client  RecommendationClient
	builder RefillFeedUnitBuilder
	config  RefillFeedConfig
	logger  *slog.Logger
}

// NewRefillFeed creates a RefillFeedUsecase.
func NewRefillFeed(units RefillFeedUnitRepository, lease *FeedLeaseManager, backlog *FeedBacklog, client RecommendationClient, builder RefillFeedUnitBuilder, config RefillFeedConfig, logger *slog.Logger) *RefillFeedUsecase {
	return &RefillFeedUsecase{units: units, lease: lease, backlog: backlog, client: client, builder: builder, config: config, logger: logger}
}

// Execute takes the lease and builds the feed, or waits for the build in flight when
// another trigger holds it. It returns a FeedTemporarilyUnavailableError when
// recommendation-service fails or the wait ends with nothing. The lease is back before
// it returns, on every path.
func (u *RefillFeedUsecase) Execute(ctx context.Context, input RefillFeedInput) (RefillFeedStatus, error) {
	if input.UserID == uuid.Nil {
		return "", fmt.Errorf("refill feed usecase: user id is required")
	}

	lease, acquired, err := u.lease.AcquireForRequest(ctx, input.UserID, input.CorrelationID)
	if err != nil {
		return "", fmt.Errorf("refill feed usecase: %w", err)
	}
	if !acquired {
		return u.waitForUnits(ctx, input)
	}

	// Release is detached from ctx, so a cancelled request still gives the lease back.
	// A failed release is logged by the manager, and the TTL covers it.
	defer func() {
		if _, err := u.lease.Release(ctx, input.UserID, lease.Token, input.CorrelationID); err != nil {
			u.logger.WarnContext(ctx, "feed refill left the lease held until its TTL", "correlation_id", input.CorrelationID, "userId", input.UserID)
		}
	}()

	return u.build(ctx, input)
}

func (u *RefillFeedUsecase) build(ctx context.Context, input RefillFeedInput) (RefillFeedStatus, error) {
	// A build may have finished, and released the lease, after the claim found nothing.
	found, err := u.units.HasAfterPosition(ctx, input.UserID.String(), input.AfterPosition, input.UnservedOnly)
	if err != nil {
		return "", fmt.Errorf("refill feed usecase: %w", err)
	}
	if found {
		return RefillFeedStatusUnitsAvailable, nil
	}

	excluded, err := u.backlog.ExcludedPostIDs(ctx, input.UserID)
	if err != nil {
		return "", fmt.Errorf("refill feed usecase: %w", err)
	}

	postIDs, err := u.client.GetRecommendedFeed(ctx, RecommendationRequest{UserID: input.UserID, ExcludedPostIDs: excluded, CorrelationID: input.CorrelationID})
	if err != nil {
		if callerWentAway(ctx) {
			return "", fmt.Errorf("refill feed usecase: %w", ctx.Err())
		}
		return "", u.degraded(ctx, input, "recommendation-service call failed", err)
	}
	if len(postIDs) == 0 {
		u.logger.InfoContext(ctx, "feed refill found no posts", "correlation_id", input.CorrelationID, "userId", input.UserID, "excluded", len(excluded))
		return RefillFeedStatusNoPosts, nil
	}

	built, err := u.builder.Execute(ctx, BuildFeedUnitsInput{UserID: input.UserID, PostIDs: postIDs, CorrelationID: input.CorrelationID})
	if err != nil {
		return "", fmt.Errorf("refill feed usecase: %w", err)
	}
	if built.Inserted == 0 {
		// Posts not projected yet, or already stored beyond the exclusions cap. Nothing to
		// serve, but the feed did not end, and an immediate claim would run the pipeline again.
		return "", u.degraded(ctx, input, "no recommended post could be stored", fmt.Errorf("%d posts returned, %d skipped", len(postIDs), len(built.Skipped)))
	}

	return RefillFeedStatusUnitsAvailable, nil
}

// waitForUnits polls the stored feed within LeaseWait. It never calls gRPC: the holder
// of the lease is building this feed.
func (u *RefillFeedUsecase) waitForUnits(ctx context.Context, input RefillFeedInput) (RefillFeedStatus, error) {
	waitCtx, cancel := context.WithTimeout(ctx, u.config.LeaseWait)
	defer cancel()
	ticker := time.NewTicker(u.config.PollInterval)
	defer ticker.Stop()

	for {
		found, err := u.units.HasAfterPosition(waitCtx, input.UserID.String(), input.AfterPosition, input.UnservedOnly)
		if err == nil && found {
			u.logger.InfoContext(ctx, "feed refill served by the build in flight", "correlation_id", input.CorrelationID, "userId", input.UserID)
			return RefillFeedStatusUnitsAvailable, nil
		}
		// A read ended by the end of the wait is not a failure of the read, even when the
		// wait's context does not report its end yet.
		if err != nil && waitCtx.Err() == nil && !errors.Is(err, ErrCallerDeadlineEndedRead) {
			return "", fmt.Errorf("refill feed usecase: %w", err)
		}

		select {
		case <-waitCtx.Done():
			if ctx.Err() != nil {
				return "", fmt.Errorf("refill feed usecase: %w", ctx.Err())
			}
			return "", u.degraded(ctx, input, "the build in flight did not finish within the wait", waitCtx.Err())
		case <-ticker.C:
		}
	}
}

func (u *RefillFeedUsecase) degraded(ctx context.Context, input RefillFeedInput, reason string, cause error) error {
	u.logger.ErrorContext(ctx, "feed degraded response", "correlation_id", input.CorrelationID, "userId", input.UserID, "reason", reason, "error", cause)
	return &FeedTemporarilyUnavailableError{RetryAfter: u.config.RetryAfter, Cause: fmt.Errorf("%s: %w", reason, cause)}
}

// callerWentAway tells a cancelled request from a failure of recommendation-service,
// which can also report itself as cancelled.
func callerWentAway(ctx context.Context) bool {
	return errors.Is(ctx.Err(), context.Canceled)
}
