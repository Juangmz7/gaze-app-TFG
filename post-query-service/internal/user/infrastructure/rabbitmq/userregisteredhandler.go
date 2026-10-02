// Package rabbitmq handles UserRegisteredEvent deliveries from
// topology.ExchangeUserEvents (routing key rk.user.registered, consumed via
// topology.QueueUserFast).
package rabbitmq

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"time"

	"github.com/ThreeDotsLabs/watermill/message"
	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/user/application/usecase"
)

// UserRegisteredEventType identifies this event for idempotency bookkeeping.
const UserRegisteredEventType = "UserRegisteredEvent"

// UserRegisteredEvent is the wire shape of UserRegisteredEvent published by
// social-service.
type UserRegisteredEvent struct {
	ID            uuid.UUID `json:"id"`
	CorrelationID uuid.UUID `json:"correlation_id"`
	OccurredAt    time.Time `json:"occurred_at"`
	UserID        uuid.UUID `json:"user_id"`
	Username      string    `json:"username"`
	CreatedAt     time.Time `json:"created_at"`
}

// UserRegisteredIdempotencyRepository records and checks processed events.
type UserRegisteredIdempotencyRepository interface {
	IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error)
	MarkProcessed(ctx context.Context, eventID, correlationID uuid.UUID, eventType string) error
}

// UserRegisteredUsecase executes the user registration projection.
type UserRegisteredUsecase interface {
	Execute(ctx context.Context, input usecase.RegisterUserInput) error
}

// UserRegisteredHandler decodes UserRegisteredEvent deliveries, enforces idempotency, and
// delegates to UserRegisteredUsecase.
type UserRegisteredHandler struct {
	idempotency UserRegisteredIdempotencyRepository
	usecase     UserRegisteredUsecase
	logger      *slog.Logger
}

// NewUserRegisteredHandler creates a UserRegisteredHandler.
func NewUserRegisteredHandler(idempotency UserRegisteredIdempotencyRepository, uc UserRegisteredUsecase, logger *slog.Logger) *UserRegisteredHandler {
	return &UserRegisteredHandler{idempotency: idempotency, usecase: uc, logger: logger}
}

// Handle implements dispatch.EventHandlerFunc.
func (h *UserRegisteredHandler) Handle(ctx context.Context, msg *message.Message) error {
	var event UserRegisteredEvent
	if err := json.Unmarshal(msg.Payload, &event); err != nil {
		return rmqerror.NewPermanent(fmt.Errorf("decode user registered event: %w", err))
	}

	if err := validateUserRegisteredEvent(event); err != nil {
		return rmqerror.NewPermanent(err)
	}

	alreadyProcessed, err := h.idempotency.IsProcessed(ctx, event.ID)
	if err != nil {
		return fmt.Errorf("check user registered event %s processed: %w", event.ID, err)
	}
	if alreadyProcessed {
		h.logger.WarnContext(ctx, "discarding duplicate user registered event", "event_id", event.ID)
		return nil
	}

	input := usecase.RegisterUserInput{
		UserID:    event.UserID,
		Username:  event.Username,
		CreatedAt: event.CreatedAt,
	}
	if err := h.usecase.Execute(ctx, input); err != nil {
		return fmt.Errorf("execute register user usecase for event %s: %w", event.ID, err)
	}

	if err := h.idempotency.MarkProcessed(ctx, event.ID, event.CorrelationID, UserRegisteredEventType); err != nil {
		return fmt.Errorf("mark user registered event %s processed: %w", event.ID, err)
	}

	return nil
}

func validateUserRegisteredEvent(event UserRegisteredEvent) error {
	if event.ID == uuid.Nil {
		return fmt.Errorf("user registered event: id is required")
	}
	if event.CorrelationID == uuid.Nil {
		return fmt.Errorf("user registered event: correlation_id is required")
	}
	if event.OccurredAt.IsZero() {
		return fmt.Errorf("user registered event: occurred_at is required")
	}
	if event.UserID == uuid.Nil {
		return fmt.Errorf("user registered event: user_id is required")
	}
	if event.Username == "" {
		return fmt.Errorf("user registered event: username is required")
	}

	return nil
}
