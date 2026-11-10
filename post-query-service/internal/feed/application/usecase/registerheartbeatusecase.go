package usecase

import (
	"context"
	"fmt"
	"log/slog"
	"time"

	"github.com/google/uuid"
)

// RegisterHeartbeatInput is the data a heartbeat carries. UserID comes from the
// authenticated context, never from the request.
type RegisterHeartbeatInput struct {
	UserID        uuid.UUID
	CorrelationID string
}

// RegisterHeartbeatRepository writes the presence document. Implemented by
// feed/infrastructure/mongo.SessionRepository.
type RegisterHeartbeatRepository interface {
	RecordHeartbeat(ctx context.Context, userID uuid.UUID, now time.Time) error
}

// RegisterHeartbeatUsecase records that a user is present and due a prefetch.
type RegisterHeartbeatUsecase struct {
	repository RegisterHeartbeatRepository
	logger     *slog.Logger
}

// NewRegisterHeartbeat creates a RegisterHeartbeatUsecase backed by repository.
func NewRegisterHeartbeat(repository RegisterHeartbeatRepository, logger *slog.Logger) *RegisterHeartbeatUsecase {
	return &RegisterHeartbeatUsecase{repository: repository, logger: logger}
}

// Execute upserts the caller's presence document.
func (u *RegisterHeartbeatUsecase) Execute(ctx context.Context, input RegisterHeartbeatInput) error {
	if input.UserID == uuid.Nil {
		return fmt.Errorf("register heartbeat usecase: user id is required")
	}

	if err := u.repository.RecordHeartbeat(ctx, input.UserID, time.Now().UTC()); err != nil {
		return fmt.Errorf("register heartbeat usecase: %w", err)
	}

	// Debug, unlike the other feed logs: every connected client sends these.
	u.logger.DebugContext(ctx, "feed heartbeat recorded", "correlation_id", input.CorrelationID, "userId", input.UserID)

	return nil
}
