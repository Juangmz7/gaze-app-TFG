package usecase

import (
	"context"
	"fmt"
	"log/slog"
	"time"

	"github.com/google/uuid"
)

// RegisterUserDisconnectedInput is the data a disconnect carries. UserID comes from
// the authenticated context, never from the request.
type RegisterUserDisconnectedInput struct {
	UserID        uuid.UUID
	CorrelationID string
}

// RegisterUserDisconnectedRepository writes the presence document. Implemented by
// feed/infrastructure/mongo.SessionRepository.
type RegisterUserDisconnectedRepository interface {
	RecordDisconnect(ctx context.Context, userID uuid.UUID, now time.Time) error
}

// RegisterUserDisconnectedRequester is RequestFeedPrefetchUsecase.
type RegisterUserDisconnectedRequester interface {
	Execute(ctx context.Context, input RequestFeedPrefetchInput) (RequestFeedPrefetchOutput, error)
}

// RegisterUserDisconnectedUsecase records the end of a user's session and asks for
// their feed to be ready when they come back.
type RegisterUserDisconnectedUsecase struct {
	repository   RegisterUserDisconnectedRepository
	prefetch     RegisterUserDisconnectedRequester
	writeTimeout time.Duration
	logger       *slog.Logger
}

// NewRegisterUserDisconnected creates a RegisterUserDisconnectedUsecase. writeTimeout
// bounds the writes, which outlive the request.
func NewRegisterUserDisconnected(repository RegisterUserDisconnectedRepository, prefetch RegisterUserDisconnectedRequester, writeTimeout time.Duration, logger *slog.Logger) *RegisterUserDisconnectedUsecase {
	return &RegisterUserDisconnectedUsecase{repository: repository, prefetch: prefetch, writeTimeout: writeTimeout, logger: logger}
}

// Execute writes the disconnect and then stores the prefetch request. Clients often
// close right after this call, so both are detached from the request's cancellation
// and share writeTimeout instead: they must not be lost when the request context is
// cancelled. Nothing here waits for the broker or for recommendation-service. A failed
// prefetch is logged, not returned: the disconnect is recorded and leaves the user due,
// so the sweeper covers it.
func (u *RegisterUserDisconnectedUsecase) Execute(ctx context.Context, input RegisterUserDisconnectedInput) error {
	if input.UserID == uuid.Nil {
		return fmt.Errorf("register user disconnected usecase: user id is required")
	}

	writeCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), u.writeTimeout)
	defer cancel()

	if err := u.repository.RecordDisconnect(writeCtx, input.UserID, time.Now().UTC()); err != nil {
		return fmt.Errorf("register user disconnected usecase: %w", err)
	}

	u.logger.InfoContext(writeCtx, "feed disconnect recorded", "correlation_id", input.CorrelationID, "userId", input.UserID)

	prefetchInput := RequestFeedPrefetchInput{UserID: input.UserID, Reason: FeedPrefetchReasonDisconnect, CorrelationID: input.CorrelationID}
	if _, err := u.prefetch.Execute(writeCtx, prefetchInput); err != nil {
		u.logger.ErrorContext(writeCtx, "feed prefetch failed after a disconnect, left to the sweeper", "correlation_id", input.CorrelationID, "userId", input.UserID, "error", err)
	}

	return nil
}
