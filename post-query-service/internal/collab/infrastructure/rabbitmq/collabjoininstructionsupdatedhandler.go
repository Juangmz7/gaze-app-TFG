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
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/eventlog"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
)

// CollabJoinInstructionsUpdatedEventType identifies this event for idempotency
// bookkeeping.
const CollabJoinInstructionsUpdatedEventType = "CollabJoinInstructionsUpdatedEvent"

// CollabJoinInstructionsUpdatedEvent is the wire shape of the Java
// CollabJoinInstructionsUpdatedEvent record post-command-service publishes on
// rk.post.collab.join-instructions.updated (task 61). Field names are camelCase.
// JoinInstructions is null when the ADMIN cleared them.
type CollabJoinInstructionsUpdatedEvent struct {
	ID               uuid.UUID `json:"id"`
	CorrelationID    uuid.UUID `json:"correlationId"`
	OccurredAt       time.Time `json:"occurredAt"`
	CollabID         uuid.UUID `json:"collabId"`
	JoinInstructions *string   `json:"joinInstructions"`
}

// CollabJoinInstructionsUpdatedIdempotencyRepository records and checks processed
// events.
type CollabJoinInstructionsUpdatedIdempotencyRepository interface {
	IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error)
	MarkProcessed(ctx context.Context, eventID, correlationID uuid.UUID, eventType string) error
}

// CollabJoinInstructionsUpdatedUsecase applies the update, reporting false when the
// event was older than the stored instructions.
type CollabJoinInstructionsUpdatedUsecase interface {
	Execute(ctx context.Context, input usecase.UpdateCollabJoinInstructionsInput) (bool, error)
}

// CollabJoinInstructionsUpdatedHandler decodes CollabJoinInstructionsUpdatedEvent
// deliveries, enforces idempotency, and delegates to
// CollabJoinInstructionsUpdatedUsecase.
type CollabJoinInstructionsUpdatedHandler struct {
	idempotency CollabJoinInstructionsUpdatedIdempotencyRepository
	usecase     CollabJoinInstructionsUpdatedUsecase
	logger      *slog.Logger
}

// NewCollabJoinInstructionsUpdatedHandler creates a
// CollabJoinInstructionsUpdatedHandler.
func NewCollabJoinInstructionsUpdatedHandler(idempotency CollabJoinInstructionsUpdatedIdempotencyRepository, uc CollabJoinInstructionsUpdatedUsecase, logger *slog.Logger) *CollabJoinInstructionsUpdatedHandler {
	return &CollabJoinInstructionsUpdatedHandler{idempotency: idempotency, usecase: uc, logger: logger}
}

// Handle implements dispatch.EventHandlerFunc. A stale event (older than the stored
// instructions) writes nothing but is still marked processed. A crash between the
// write and MarkProcessed is harmless: the redelivery compares equal and is stale.
func (h *CollabJoinInstructionsUpdatedHandler) Handle(ctx context.Context, msg *message.Message) error {
	var event CollabJoinInstructionsUpdatedEvent
	if err := json.Unmarshal(msg.Payload, &event); err != nil {
		eventlog.WarnInvalid(ctx, h.logger, CollabJoinInstructionsUpdatedEventType, msg.Payload, err)
		return rmqerror.NewPermanent(fmt.Errorf("decode collab join instructions updated event: %w", err))
	}

	if err := validateCollabJoinInstructionsUpdatedEvent(event); err != nil {
		eventlog.WarnInvalid(ctx, h.logger, CollabJoinInstructionsUpdatedEventType, msg.Payload, err)
		return rmqerror.NewPermanent(err)
	}

	alreadyProcessed, err := h.idempotency.IsProcessed(ctx, event.ID)
	if err != nil {
		return fmt.Errorf("check collab join instructions updated event %s processed: %w", event.ID, err)
	}
	if alreadyProcessed {
		h.logger.WarnContext(ctx, "discarding duplicate collab join instructions updated event", "event_id", event.ID, "correlation_id", event.CorrelationID, "collab_id", event.CollabID)
		return nil
	}

	input := usecase.UpdateCollabJoinInstructionsInput{CollabID: event.CollabID, OccurredAt: event.OccurredAt}
	if event.JoinInstructions != nil {
		input.JoinInstructions = *event.JoinInstructions
	}
	applied, err := h.usecase.Execute(ctx, input)
	if err != nil {
		return fmt.Errorf("execute update collab join instructions usecase for event %s: %w", event.ID, err)
	}

	if err := h.idempotency.MarkProcessed(ctx, event.ID, event.CorrelationID, CollabJoinInstructionsUpdatedEventType); err != nil {
		return fmt.Errorf("mark collab join instructions updated event %s processed: %w", event.ID, err)
	}

	if !applied {
		h.logger.InfoContext(ctx, "stale collab join instructions updated event skipped", "event_id", event.ID, "correlation_id", event.CorrelationID, "collab_id", event.CollabID)
		return nil
	}
	h.logger.InfoContext(ctx, "collab join instructions updated event projected", "event_id", event.ID, "correlation_id", event.CorrelationID, "collab_id", event.CollabID)

	return nil
}

func validateCollabJoinInstructionsUpdatedEvent(event CollabJoinInstructionsUpdatedEvent) error {
	if event.ID == uuid.Nil {
		return fmt.Errorf("collab join instructions updated event: id is required")
	}
	if event.CorrelationID == uuid.Nil {
		return fmt.Errorf("collab join instructions updated event: correlationId is required")
	}
	if event.OccurredAt.IsZero() {
		return fmt.Errorf("collab join instructions updated event: occurredAt is required")
	}
	if event.CollabID == uuid.Nil {
		return fmt.Errorf("collab join instructions updated event: collabId is required")
	}

	return nil
}
