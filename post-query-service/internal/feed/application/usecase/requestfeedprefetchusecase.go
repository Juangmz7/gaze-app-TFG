package usecase

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/google/uuid"
)

// FeedPrefetchReason says what triggered a prefetch. The values are part of the
// contract with recommendation-service.
type FeedPrefetchReason string

// The asynchronous triggers.
const (
	FeedPrefetchReasonDisconnect     FeedPrefetchReason = "disconnect"
	FeedPrefetchReasonStaleHeartbeat FeedPrefetchReason = "stale_heartbeat"
	FeedPrefetchReasonUserRegistered FeedPrefetchReason = "user_registered"
	FeedPrefetchReasonLowWatermark   FeedPrefetchReason = "low_watermark"
)

func (r FeedPrefetchReason) valid() bool {
	switch r {
	case FeedPrefetchReasonDisconnect, FeedPrefetchReasonStaleHeartbeat, FeedPrefetchReasonUserRegistered, FeedPrefetchReasonLowWatermark:
		return true
	}
	return false
}

// RequestFeedPrefetchStatus says how a prefetch request ended. All of them are clean
// outcomes.
type RequestFeedPrefetchStatus string

// The outcomes of a prefetch request.
const (
	// RequestFeedPrefetchPublished: the lease is held and the event is in the outbox.
	RequestFeedPrefetchPublished RequestFeedPrefetchStatus = "published"
	// RequestFeedPrefetchSkipped: the user already has the maximum of unserved items.
	RequestFeedPrefetchSkipped RequestFeedPrefetchStatus = "skipped"
	// RequestFeedPrefetchDenied: another trigger holds the lease.
	RequestFeedPrefetchDenied RequestFeedPrefetchStatus = "denied"
)

// RequestFeedPrefetchInput asks for a user's feed to be built asynchronously.
// CorrelationID is the caller's, for logs; the event carries the lease token instead.
type RequestFeedPrefetchInput struct {
	UserID        uuid.UUID
	Reason        FeedPrefetchReason
	CorrelationID string
}

// RequestFeedPrefetchOutput reports the outcome. LeaseToken is set when published.
type RequestFeedPrefetchOutput struct {
	Status          RequestFeedPrefetchStatus
	LeaseToken      uuid.UUID
	CurrentFeedSize int64
}

// FeedPrefetchRequest is the message to recommendation-service. CorrelationID is the
// lease token: the response echoes it.
type FeedPrefetchRequest struct {
	CorrelationID   uuid.UUID
	UserID          uuid.UUID
	Reason          FeedPrefetchReason
	CurrentFeedSize int64
	ExcludedPostIDs []uuid.UUID
}

// RequestFeedPrefetchTransactor runs the check, the lease and the outbox write in one
// transaction (database.Transactor). fn must be safe to re-run.
type RequestFeedPrefetchTransactor interface {
	WithTransaction(ctx context.Context, fn func(ctx context.Context) error) error
}

// RequestFeedPrefetchSessionRepository keeps the sweeper's marker. Implemented by
// feed/infrastructure/mongo.SessionRepository.
type RequestFeedPrefetchSessionRepository interface {
	MarkPrefetched(ctx context.Context, userID uuid.UUID, now time.Time) error
	ClearPrefetchDue(ctx context.Context, userID uuid.UUID) error
}

// RequestFeedPrefetchPublisher stores the request in the outbox, with the context of
// the transaction. Implemented by feed/infrastructure/rabbitmq.FeedPrefetchPublisher.
type RequestFeedPrefetchPublisher interface {
	PublishFeedPrefetch(ctx context.Context, request FeedPrefetchRequest) error
}

// RequestFeedPrefetchLease takes the lease. Implemented by FeedLeaseManager.
type RequestFeedPrefetchLease interface {
	Acquire(ctx context.Context, userID uuid.UUID) (FeedLease, error)
}

// RequestFeedPrefetchUsecase is the one way into an asynchronous feed build, for every
// trigger: the unserved-count check, the lease and the outbox write.
type RequestFeedPrefetchUsecase struct {
	transactor RequestFeedPrefetchTransactor
	backlog    *FeedBacklog
	lease      RequestFeedPrefetchLease
	sessions   RequestFeedPrefetchSessionRepository
	publisher  RequestFeedPrefetchPublisher
	timeout    time.Duration
	logger     *slog.Logger
}

// NewRequestFeedPrefetch creates a RequestFeedPrefetchUsecase. timeout bounds the
// whole transaction, including the driver's retries of a transient error.
func NewRequestFeedPrefetch(transactor RequestFeedPrefetchTransactor, backlog *FeedBacklog, lease RequestFeedPrefetchLease, sessions RequestFeedPrefetchSessionRepository, publisher RequestFeedPrefetchPublisher, timeout time.Duration, logger *slog.Logger) *RequestFeedPrefetchUsecase {
	return &RequestFeedPrefetchUsecase{transactor: transactor, backlog: backlog, lease: lease, sessions: sessions, publisher: publisher, timeout: timeout, logger: logger}
}

// Execute runs one transaction, so a held lease always has a published request: an
// outbox failure rolls the lease back too. A user at the maximum of unserved items is
// skipped and no longer due. A held lease aborts the transaction and is a clean
// outcome, and prefetch_due stays as it is, because the build in flight clears it.
func (u *RequestFeedPrefetchUsecase) Execute(ctx context.Context, input RequestFeedPrefetchInput) (RequestFeedPrefetchOutput, error) {
	if input.UserID == uuid.Nil {
		return RequestFeedPrefetchOutput{}, fmt.Errorf("request feed prefetch usecase: user id is required")
	}
	if !input.Reason.valid() {
		return RequestFeedPrefetchOutput{}, fmt.Errorf("request feed prefetch usecase: unknown reason %q", input.Reason)
	}

	ctx, cancel := context.WithTimeout(ctx, u.timeout)
	defer cancel()

	var output RequestFeedPrefetchOutput
	err := u.transactor.WithTransaction(ctx, func(ctx context.Context) error {
		var err error
		output, err = u.requestInTransaction(ctx, input)
		return err
	})
	if errors.Is(err, ErrFeedLeaseDenied) {
		u.logger.InfoContext(ctx, "feed prefetch not requested, the lease is held", "correlation_id", input.CorrelationID, "userId", input.UserID, "reason", input.Reason)
		return RequestFeedPrefetchOutput{Status: RequestFeedPrefetchDenied}, nil
	}
	if err != nil {
		return RequestFeedPrefetchOutput{}, fmt.Errorf("request feed prefetch usecase: %w", err)
	}

	// Logged after the commit, because the driver may re-run the function.
	if output.Status == RequestFeedPrefetchSkipped {
		u.logger.InfoContext(ctx, "feed prefetch skipped, the user has the maximum of unserved items", "correlation_id", input.CorrelationID, "userId", input.UserID, "reason", input.Reason, "unserved", output.CurrentFeedSize)
		return output, nil
	}
	u.logger.InfoContext(ctx, "feed lease acquired and prefetch published", "correlation_id", input.CorrelationID, "userId", input.UserID, "reason", input.Reason, "lease_token", output.LeaseToken, "unserved", output.CurrentFeedSize)

	return output, nil
}

// RequestUserRegisteredPrefetch is Execute for a newly registered user, the port the
// user context declares without importing the feed.
func (u *RequestFeedPrefetchUsecase) RequestUserRegisteredPrefetch(ctx context.Context, userID uuid.UUID, correlationID string) error {
	_, err := u.Execute(ctx, RequestFeedPrefetchInput{UserID: userID, Reason: FeedPrefetchReasonUserRegistered, CorrelationID: correlationID})
	return err
}

func (u *RequestFeedPrefetchUsecase) requestInTransaction(ctx context.Context, input RequestFeedPrefetchInput) (RequestFeedPrefetchOutput, error) {
	unserved, full, err := u.backlog.Unserved(ctx, input.UserID)
	if err != nil {
		return RequestFeedPrefetchOutput{}, err
	}
	if full {
		if err := u.sessions.ClearPrefetchDue(ctx, input.UserID); err != nil {
			return RequestFeedPrefetchOutput{}, err
		}
		return RequestFeedPrefetchOutput{Status: RequestFeedPrefetchSkipped, CurrentFeedSize: unserved}, nil
	}

	excluded, err := u.backlog.ExcludedPostIDs(ctx, input.UserID)
	if err != nil {
		return RequestFeedPrefetchOutput{}, err
	}

	lease, err := u.lease.Acquire(ctx, input.UserID)
	if err != nil {
		return RequestFeedPrefetchOutput{}, err
	}

	// Without clearing it here, a user whose response is lost would be queued again by
	// every sweep. A later heartbeat sets it again.
	if err := u.sessions.MarkPrefetched(ctx, input.UserID, time.Now().UTC()); err != nil {
		return RequestFeedPrefetchOutput{}, err
	}

	request := FeedPrefetchRequest{
		CorrelationID:   lease.Token,
		UserID:          input.UserID,
		Reason:          input.Reason,
		CurrentFeedSize: unserved,
		ExcludedPostIDs: excluded,
	}
	if err := u.publisher.PublishFeedPrefetch(ctx, request); err != nil {
		return RequestFeedPrefetchOutput{}, err
	}

	return RequestFeedPrefetchOutput{Status: RequestFeedPrefetchPublished, LeaseToken: lease.Token, CurrentFeedSize: unserved}, nil
}
