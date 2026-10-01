// Package userdeleted handles UserDeletedEvent deliveries from
// topology.ExchangeUserEvents (routing key rk.user.deleted, consumed via
// topology.QueueUserSlow).
package userdeleted

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"time"

	"github.com/ThreeDotsLabs/watermill/message"
	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/user/application/usecase/deleteuser"
)

// EventType identifies this event for idempotency bookkeeping.
const EventType = "UserDeletedEvent"

// Event is the wire shape of UserDeletedEvent published by social-service.
type Event struct {
	ID            uuid.UUID `json:"id"`
	CorrelationID uuid.UUID `json:"correlation_id"`
	OccurredAt    time.Time `json:"occurred_at"`
	UserID        uuid.UUID `json:"user_id"`
}

// IdempotencyRepository records and checks processed events.
type IdempotencyRepository interface {
	IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error)
	MarkProcessed(ctx context.Context, eventID, correlationID uuid.UUID, eventType string) error
}

// Usecase executes the user deletion projection.
type Usecase interface {
	Execute(ctx context.Context, input deleteuser.Input) error
}

// Handler decodes UserDeletedEvent deliveries, enforces idempotency, and
// delegates to Usecase.
type Handler struct {
	idempotency IdempotencyRepository
	usecase     Usecase
	logger      *slog.Logger
}

// New creates a Handler.
func New(idempotency IdempotencyRepository, usecase Usecase, logger *slog.Logger) *Handler {
	return &Handler{idempotency: idempotency, usecase: usecase, logger: logger}
}

// Handle implements dispatch.EventHandlerFunc.
func (h *Handler) Handle(ctx context.Context, msg *message.Message) error {
	var event Event
	if err := json.Unmarshal(msg.Payload, &event); err != nil {
		return rmqerror.NewPermanent(fmt.Errorf("decode user deleted event: %w", err))
	}

	if err := validate(event); err != nil {
		return rmqerror.NewPermanent(err)
	}

	alreadyProcessed, err := h.idempotency.IsProcessed(ctx, event.ID)
	if err != nil {
		return fmt.Errorf("check user deleted event %s processed: %w", event.ID, err)
	}
	if alreadyProcessed {
		h.logger.WarnContext(ctx, "discarding duplicate user deleted event", "event_id", event.ID)
		return nil
	}

	input := deleteuser.Input{UserID: event.UserID}
	if err := h.usecase.Execute(ctx, input); err != nil {
		return fmt.Errorf("execute delete user usecase for event %s: %w", event.ID, err)
	}

	if err := h.idempotency.MarkProcessed(ctx, event.ID, event.CorrelationID, EventType); err != nil {
		return fmt.Errorf("mark user deleted event %s processed: %w", event.ID, err)
	}

	return nil
}

func validate(event Event) error {
	if event.ID == uuid.Nil {
		return fmt.Errorf("user deleted event: id is required")
	}
	if event.CorrelationID == uuid.Nil {
		return fmt.Errorf("user deleted event: correlation_id is required")
	}
	if event.OccurredAt.IsZero() {
		return fmt.Errorf("user deleted event: occurred_at is required")
	}
	if event.UserID == uuid.Nil {
		return fmt.Errorf("user deleted event: user_id is required")
	}

	return nil
}
