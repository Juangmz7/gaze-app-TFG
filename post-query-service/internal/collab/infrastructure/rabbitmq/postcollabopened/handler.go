// Package postcollabopened handles the post-created variant of
// PostCollabOpenedEvent from topology.ExchangePostEvents (routing key
// rk.post.collab.opened.post-created).
package postcollabopened

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"time"

	"github.com/ThreeDotsLabs/watermill/message"
	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/application/usecase/recordcollabopened"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
)

// EventType identifies this event for idempotency bookkeeping.
const EventType = "PostCollabOpenedPostCreatedEvent"

// Event is the wire shape of the post-created variant of
// PostCollabOpenedEvent published by post-command-service.
type Event struct {
	ID            uuid.UUID `json:"id"`
	CorrelationID uuid.UUID `json:"correlation_id"`
	OccurredAt    time.Time `json:"occurred_at"`
	CollabID      uuid.UUID `json:"collab_id"`
	PostID        uuid.UUID `json:"post_id"`
	OwnerUserID   uuid.UUID `json:"owner_user_id"`
	CreatedAt     time.Time `json:"created_at"`
}

// IdempotencyRepository records and checks processed events.
type IdempotencyRepository interface {
	IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error)
	MarkProcessed(ctx context.Context, eventID, correlationID uuid.UUID, eventType string) error
}

// Usecase executes the collab projection.
type Usecase interface {
	Execute(ctx context.Context, input recordcollabopened.Input) error
}

// Handler decodes the event, enforces idempotency, and delegates to Usecase.
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
		return rmqerror.NewPermanent(fmt.Errorf("decode post collab opened event: %w", err))
	}

	if err := validate(event); err != nil {
		return rmqerror.NewPermanent(err)
	}

	alreadyProcessed, err := h.idempotency.IsProcessed(ctx, event.ID)
	if err != nil {
		return fmt.Errorf("check post collab opened event %s processed: %w", event.ID, err)
	}
	if alreadyProcessed {
		h.logger.WarnContext(ctx, "discarding duplicate post collab opened event", "event_id", event.ID)
		return nil
	}

	input := recordcollabopened.Input{
		CollabID:    event.CollabID,
		PostID:      event.PostID,
		OwnerUserID: event.OwnerUserID,
		CreatedAt:   event.CreatedAt,
	}
	if err := h.usecase.Execute(ctx, input); err != nil {
		return fmt.Errorf("execute record collab opened usecase for event %s: %w", event.ID, err)
	}

	if err := h.idempotency.MarkProcessed(ctx, event.ID, event.CorrelationID, EventType); err != nil {
		return fmt.Errorf("mark post collab opened event %s processed: %w", event.ID, err)
	}

	return nil
}

func validate(event Event) error {
	if event.ID == uuid.Nil {
		return fmt.Errorf("post collab opened event: id is required")
	}
	if event.CorrelationID == uuid.Nil {
		return fmt.Errorf("post collab opened event: correlation_id is required")
	}
	if event.OccurredAt.IsZero() {
		return fmt.Errorf("post collab opened event: occurred_at is required")
	}
	if event.CollabID == uuid.Nil {
		return fmt.Errorf("post collab opened event: collab_id is required")
	}
	if event.PostID == uuid.Nil {
		return fmt.Errorf("post collab opened event: post_id is required")
	}
	if event.OwnerUserID == uuid.Nil {
		return fmt.Errorf("post collab opened event: owner_user_id is required")
	}

	return nil
}
