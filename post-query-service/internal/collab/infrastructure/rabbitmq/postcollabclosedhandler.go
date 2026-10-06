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

// PostCollabClosedEventType identifies this event for idempotency
// bookkeeping.
const PostCollabClosedEventType = "CollabClosedEvent"

// PostCollabClosedEvent is the wire shape of CollabClosedEvent published by
// post-command-service. Field names are camelCase, matching the real Java
// CollabClosedEvent record.
type PostCollabClosedEvent struct {
	ID              uuid.UUID `json:"id"`
	CorrelationID   uuid.UUID `json:"correlationId"`
	OccurredAt      time.Time `json:"occurredAt"`
	CollabID        uuid.UUID `json:"collabId"`
	Title           string    `json:"title"`
	CreatedBy       uuid.UUID `json:"createdBy"`
	ClosedBy        uuid.UUID `json:"closedBy"`
	CollabStatus    string    `json:"collabStatus"`
	CollabCreatedAt time.Time `json:"collabCreatedAt"`
}

// PostCollabClosedIdempotencyRepository records and checks processed
// events.
type PostCollabClosedIdempotencyRepository interface {
	IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error)
	MarkProcessed(ctx context.Context, eventID, correlationID uuid.UUID, eventType string) error
}

// PostCollabClosedUsecase executes the collab-closed projection.
type PostCollabClosedUsecase interface {
	Execute(ctx context.Context, input usecase.CloseCollabInput) error
}

// PostCollabClosedHandler decodes PostCollabClosedEvent deliveries,
// enforces idempotency, and delegates to PostCollabClosedUsecase.
type PostCollabClosedHandler struct {
	idempotency PostCollabClosedIdempotencyRepository
	usecase     PostCollabClosedUsecase
	logger      *slog.Logger
}

// NewPostCollabClosedHandler creates a PostCollabClosedHandler.
func NewPostCollabClosedHandler(idempotency PostCollabClosedIdempotencyRepository, uc PostCollabClosedUsecase, logger *slog.Logger) *PostCollabClosedHandler {
	return &PostCollabClosedHandler{idempotency: idempotency, usecase: uc, logger: logger}
}

// Handle implements dispatch.EventHandlerFunc.
func (h *PostCollabClosedHandler) Handle(ctx context.Context, msg *message.Message) error {
	var event PostCollabClosedEvent
	if err := json.Unmarshal(msg.Payload, &event); err != nil {
		return rmqerror.NewPermanent(fmt.Errorf("decode post collab closed event: %w", err))
	}

	if err := validatePostCollabClosedEvent(event); err != nil {
		return rmqerror.NewPermanent(err)
	}

	alreadyProcessed, err := h.idempotency.IsProcessed(ctx, event.ID)
	if err != nil {
		return fmt.Errorf("check post collab closed event %s processed: %w", event.ID, err)
	}
	if alreadyProcessed {
		h.logger.WarnContext(ctx, "discarding duplicate post collab closed event", "event_id", event.ID)
		return nil
	}

	input := usecase.CloseCollabInput{
		CollabID:        event.CollabID,
		Title:           event.Title,
		CreatedBy:       event.CreatedBy,
		ClosedBy:        event.ClosedBy,
		Status:          event.CollabStatus,
		CollabCreatedAt: event.CollabCreatedAt,
	}
	if err := h.usecase.Execute(ctx, input); err != nil {
		return fmt.Errorf("execute close collab usecase for event %s: %w", event.ID, err)
	}

	if err := h.idempotency.MarkProcessed(ctx, event.ID, event.CorrelationID, PostCollabClosedEventType); err != nil {
		return fmt.Errorf("mark post collab closed event %s processed: %w", event.ID, err)
	}

	return nil
}

func validatePostCollabClosedEvent(event PostCollabClosedEvent) error {
	if event.ID == uuid.Nil {
		return fmt.Errorf("post collab closed event: id is required")
	}
	if event.CorrelationID == uuid.Nil {
		return fmt.Errorf("post collab closed event: correlationId is required")
	}
	if event.OccurredAt.IsZero() {
		return fmt.Errorf("post collab closed event: occurredAt is required")
	}
	if event.CollabID == uuid.Nil {
		return fmt.Errorf("post collab closed event: collabId is required")
	}
	if event.CreatedBy == uuid.Nil {
		return fmt.Errorf("post collab closed event: createdBy is required")
	}
	if event.ClosedBy == uuid.Nil {
		return fmt.Errorf("post collab closed event: closedBy is required")
	}
	if event.CollabStatus == "" {
		return fmt.Errorf("post collab closed event: collabStatus is required")
	}

	return nil
}
