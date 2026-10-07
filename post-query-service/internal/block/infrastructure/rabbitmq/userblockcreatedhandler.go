// Package rabbitmq handles UserBlockedEvent and UserUnblockedEvent
// deliveries from topology.ExchangeUserEvents (routing keys
// rk.user.block.created via topology.QueueUserFast and
// rk.user.block.deleted via topology.QueueUserSlow).
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

// UserBlockCreatedEventType identifies this event for idempotency
// bookkeeping.
const UserBlockCreatedEventType = "UserBlockedEvent"

// UserBlockCreatedEvent is the wire shape of UserBlockedEvent published by
// social-service. Field names are camelCase, matching the real Java
// UserBlockedEvent record (plain record, no Jackson naming-strategy
// override).
type UserBlockCreatedEvent struct {
	ID            uuid.UUID `json:"id"`
	CorrelationID uuid.UUID `json:"correlationId"`
	OccurredAt    time.Time `json:"occurredAt"`
	BlockerUserID uuid.UUID `json:"blockerUserId"`
	BlockedUserID uuid.UUID `json:"blockedUserId"`
}

// UserBlockCreatedIdempotencyRepository records and checks processed
// events.
type UserBlockCreatedIdempotencyRepository interface {
	IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error)
	MarkProcessed(ctx context.Context, eventID, correlationID uuid.UUID, eventType string) error
}

// UserBlockCreatedUsecase executes the block creation projection.
type UserBlockCreatedUsecase interface {
	Execute(ctx context.Context, input usecase.CreateBlockInput) error
}

// UserBlockCreatedHandler decodes UserBlockCreatedEvent deliveries,
// enforces idempotency, and delegates to UserBlockCreatedUsecase.
type UserBlockCreatedHandler struct {
	idempotency UserBlockCreatedIdempotencyRepository
	usecase     UserBlockCreatedUsecase
	logger      *slog.Logger
}

// NewUserBlockCreatedHandler creates a UserBlockCreatedHandler.
func NewUserBlockCreatedHandler(idempotency UserBlockCreatedIdempotencyRepository, uc UserBlockCreatedUsecase, logger *slog.Logger) *UserBlockCreatedHandler {
	return &UserBlockCreatedHandler{idempotency: idempotency, usecase: uc, logger: logger}
}

// Handle implements dispatch.EventHandlerFunc.
func (h *UserBlockCreatedHandler) Handle(ctx context.Context, msg *message.Message) error {
	var event UserBlockCreatedEvent
	if err := json.Unmarshal(msg.Payload, &event); err != nil {
		return rmqerror.NewPermanent(fmt.Errorf("decode user block created event: %w", err))
	}

	if err := validateUserBlockCreatedEvent(event); err != nil {
		return rmqerror.NewPermanent(err)
	}

	alreadyProcessed, err := h.idempotency.IsProcessed(ctx, event.ID)
	if err != nil {
		return fmt.Errorf("check user block created event %s processed: %w", event.ID, err)
	}
	if alreadyProcessed {
		h.logger.WarnContext(ctx, "discarding duplicate user block created event", "event_id", event.ID)
		return nil
	}

	input := usecase.CreateBlockInput{
		BlockerUserID: event.BlockerUserID,
		BlockedUserID: event.BlockedUserID,
		CreatedAt:     event.OccurredAt,
	}
	if err := h.usecase.Execute(ctx, input); err != nil {
		return fmt.Errorf("execute create block usecase for event %s: %w", event.ID, err)
	}

	if err := h.idempotency.MarkProcessed(ctx, event.ID, event.CorrelationID, UserBlockCreatedEventType); err != nil {
		return fmt.Errorf("mark user block created event %s processed: %w", event.ID, err)
	}

	return nil
}

func validateUserBlockCreatedEvent(event UserBlockCreatedEvent) error {
	if event.ID == uuid.Nil {
		return fmt.Errorf("user block created event: id is required")
	}
	if event.CorrelationID == uuid.Nil {
		return fmt.Errorf("user block created event: correlationId is required")
	}
	if event.OccurredAt.IsZero() {
		return fmt.Errorf("user block created event: occurredAt is required")
	}
	if event.BlockerUserID == uuid.Nil {
		return fmt.Errorf("user block created event: blockerUserId is required")
	}
	if event.BlockedUserID == uuid.Nil {
		return fmt.Errorf("user block created event: blockedUserId is required")
	}

	return nil
}
