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

// RegisterUserDisconnectedUsecase records the end of a user's session.
type RegisterUserDisconnectedUsecase struct {
	repository   RegisterUserDisconnectedRepository
	writeTimeout time.Duration
	logger       *slog.Logger
}

// NewRegisterUserDisconnected creates a RegisterUserDisconnectedUsecase. writeTimeout
// bounds the write, which outlives the request.
func NewRegisterUserDisconnected(repository RegisterUserDisconnectedRepository, writeTimeout time.Duration, logger *slog.Logger) *RegisterUserDisconnectedUsecase {
	return &RegisterUserDisconnectedUsecase{repository: repository, writeTimeout: writeTimeout, logger: logger}
}

// Execute writes the disconnect. Clients often close right after this call, so the
// write is detached from the request's cancellation and bounded by writeTimeout
// instead: it must not be lost when the request context is cancelled.
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

	return nil
}
