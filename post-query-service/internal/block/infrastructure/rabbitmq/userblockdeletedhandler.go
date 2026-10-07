package rabbitmq

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"time"

	"github.com/ThreeDotsLabs/watermill/message"
	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/block/application/usecase"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
)

// UserBlockDeletedEventType identifies this event for idempotency
// bookkeeping.
const UserBlockDeletedEventType = "UserUnblockedEvent"

// UserBlockDeletedEvent is the wire shape of UserUnblockedEvent published by
// social-service. Field names are camelCase, matching the real Java
// UserUnblockedEvent record.
type UserBlockDeletedEvent struct {
	ID            uuid.UUID `json:"id"`
	CorrelationID uuid.UUID `json:"correlationId"`
	OccurredAt    time.Time `json:"occurredAt"`
	BlockerUserID uuid.UUID `json:"blockerUserId"`
	BlockedUserID uuid.UUID `json:"blockedUserId"`
}

// UserBlockDeletedIdempotencyRepository records and checks processed
// events.
type UserBlockDeletedIdempotencyRepository interface {
	IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error)
	MarkProcessed(ctx context.Context, eventID, correlationID uuid.UUID, eventType string) error
}

// UserBlockDeletedUsecase executes the block deletion projection.
type UserBlockDeletedUsecase interface {
	Execute(ctx context.Context, input usecase.DeleteBlockInput) error
}

// UserBlockDeletedHandler decodes UserBlockDeletedEvent deliveries,
// enforces idempotency, and delegates to UserBlockDeletedUsecase.
type UserBlockDeletedHandler struct {
	idempotency UserBlockDeletedIdempotencyRepository
	usecase     UserBlockDeletedUsecase
	logger      *slog.Logger
}

// NewUserBlockDeletedHandler creates a UserBlockDeletedHandler.
func NewUserBlockDeletedHandler(idempotency UserBlockDeletedIdempotencyRepository, uc UserBlockDeletedUsecase, logger *slog.Logger) *UserBlockDeletedHandler {
	return &UserBlockDeletedHandler{idempotency: idempotency, usecase: uc, logger: logger}
}

// Handle implements dispatch.EventHandlerFunc.
func (h *UserBlockDeletedHandler) Handle(ctx context.Context, msg *message.Message) error {
	var event UserBlockDeletedEvent
	if err := json.Unmarshal(msg.Payload, &event); err != nil {
		return rmqerror.NewPermanent(fmt.Errorf("decode user block deleted event: %w", err))
	}

	if err := validateUserBlockDeletedEvent(event); err != nil {
		return rmqerror.NewPermanent(err)
	}

	alreadyProcessed, err := h.idempotency.IsProcessed(ctx, event.ID)
	if err != nil {
		return fmt.Errorf("check user block deleted event %s processed: %w", event.ID, err)
	}
	if alreadyProcessed {
		h.logger.WarnContext(ctx, "discarding duplicate user block deleted event", "event_id", event.ID)
		return nil
	}

	input := usecase.DeleteBlockInput{
		BlockerUserID: event.BlockerUserID,
		BlockedUserID: event.BlockedUserID,
	}
	if err := h.usecase.Execute(ctx, input); err != nil {
		return fmt.Errorf("execute delete block usecase for event %s: %w", event.ID, err)
	}

	if err := h.idempotency.MarkProcessed(ctx, event.ID, event.CorrelationID, UserBlockDeletedEventType); err != nil {
		return fmt.Errorf("mark user block deleted event %s processed: %w", event.ID, err)
	}

	return nil
}

func validateUserBlockDeletedEvent(event UserBlockDeletedEvent) error {
	if event.ID == uuid.Nil {
		return fmt.Errorf("user block deleted event: id is required")
	}
	if event.CorrelationID == uuid.Nil {
		return fmt.Errorf("user block deleted event: correlationId is required")
	}
	if event.OccurredAt.IsZero() {
		return fmt.Errorf("user block deleted event: occurredAt is required")
	}
	if event.BlockerUserID == uuid.Nil {
		return fmt.Errorf("user block deleted event: blockerUserId is required")
	}
	if event.BlockedUserID == uuid.Nil {
		return fmt.Errorf("user block deleted event: blockedUserId is required")
	}

	return nil
}
