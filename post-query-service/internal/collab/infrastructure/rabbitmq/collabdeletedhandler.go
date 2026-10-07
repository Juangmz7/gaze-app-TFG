package rabbitmq

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"time"

	"github.com/ThreeDotsLabs/watermill/message"
	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/application/usecase"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
)

// CollabDeletedEventType identifies this event for idempotency
// bookkeeping.
const CollabDeletedEventType = "CollabDeletedEvent"

// CollabDeletedEvent is the wire shape of CollabDeletedEvent published by
// post-command-service. Field names are camelCase, matching the real Java
// CollabDeletedEvent record.
type CollabDeletedEvent struct {
	ID            uuid.UUID `json:"id"`
	CorrelationID uuid.UUID `json:"correlationId"`
	CollabID      uuid.UUID `json:"collabId"`
	ActionedBy    uuid.UUID `json:"actionedBy"`
	OccurredAt    time.Time `json:"occurredAt"`
}

// CollabDeletedIdempotencyRepository records and checks processed events.
type CollabDeletedIdempotencyRepository interface {
	IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error)
	MarkProcessed(ctx context.Context, eventID, correlationID uuid.UUID, eventType string) error
}

// CollabDeletedUsecase executes the collab deletion projection.
type CollabDeletedUsecase interface {
	Execute(ctx context.Context, input usecase.DeleteCollabInput) error
}

// CollabDeletedHandler decodes CollabDeletedEvent deliveries, enforces
// idempotency, and delegates to CollabDeletedUsecase.
type CollabDeletedHandler struct {
	idempotency CollabDeletedIdempotencyRepository
	usecase     CollabDeletedUsecase
	logger      *slog.Logger
}

// NewCollabDeletedHandler creates a CollabDeletedHandler.
func NewCollabDeletedHandler(idempotency CollabDeletedIdempotencyRepository, uc CollabDeletedUsecase, logger *slog.Logger) *CollabDeletedHandler {
	return &CollabDeletedHandler{idempotency: idempotency, usecase: uc, logger: logger}
}

// Handle implements dispatch.EventHandlerFunc.
func (h *CollabDeletedHandler) Handle(ctx context.Context, msg *message.Message) error {
	var event CollabDeletedEvent
	if err := json.Unmarshal(msg.Payload, &event); err != nil {
		return rmqerror.NewPermanent(fmt.Errorf("decode collab deleted event: %w", err))
	}

	if err := validateCollabDeletedEvent(event); err != nil {
		return rmqerror.NewPermanent(err)
	}

	alreadyProcessed, err := h.idempotency.IsProcessed(ctx, event.ID)
	if err != nil {
		return fmt.Errorf("check collab deleted event %s processed: %w", event.ID, err)
	}
	if alreadyProcessed {
		h.logger.WarnContext(ctx, "discarding duplicate collab deleted event", "event_id", event.ID)
		return nil
	}

	input := usecase.DeleteCollabInput{CollabID: event.CollabID}
	if err := h.usecase.Execute(ctx, input); err != nil {
		return fmt.Errorf("execute delete collab usecase for event %s: %w", event.ID, err)
	}

	if err := h.idempotency.MarkProcessed(ctx, event.ID, event.CorrelationID, CollabDeletedEventType); err != nil {
		return fmt.Errorf("mark collab deleted event %s processed: %w", event.ID, err)
	}

	return nil
}

func validateCollabDeletedEvent(event CollabDeletedEvent) error {
	if event.ID == uuid.Nil {
		return fmt.Errorf("collab deleted event: id is required")
	}
	if event.CorrelationID == uuid.Nil {
		return fmt.Errorf("collab deleted event: correlationId is required")
	}
	if event.OccurredAt.IsZero() {
		return fmt.Errorf("collab deleted event: occurredAt is required")
	}
	if event.CollabID == uuid.Nil {
		return fmt.Errorf("collab deleted event: collabId is required")
	}
	if event.ActionedBy == uuid.Nil {
		return fmt.Errorf("collab deleted event: actionedBy is required")
	}

	return nil
}
