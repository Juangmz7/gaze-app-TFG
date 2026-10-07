// Package rabbitmq handles the post-created variant of
// PostCollabOpenedEvent and CollabClosedEvent from
// topology.ExchangePostEvents (routing keys
// rk.post.collab.opened.post-created and rk.post.collab.closed).
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

// PostCollabOpenedEventType identifies this event for idempotency
// bookkeeping.
const PostCollabOpenedEventType = "PostCollabOpenedPostCreatedEvent"

// PostCollabOpenedEvent is the wire shape of the post-created variant of
// PostCollabOpenedEvent published by post-command-service.
type PostCollabOpenedEvent struct {
	ID            uuid.UUID `json:"id"`
	CorrelationID uuid.UUID `json:"correlation_id"`
	OccurredAt    time.Time `json:"occurred_at"`
	CollabID      uuid.UUID `json:"collab_id"`
	PostID        uuid.UUID `json:"post_id"`
	OwnerUserID   uuid.UUID `json:"owner_user_id"`
	CreatedAt     time.Time `json:"created_at"`
}

// PostCollabOpenedIdempotencyRepository records and checks processed
// events.
type PostCollabOpenedIdempotencyRepository interface {
	IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error)
	MarkProcessed(ctx context.Context, eventID, correlationID uuid.UUID, eventType string) error
}

// PostCollabOpenedUsecase executes the collab creation projection.
type PostCollabOpenedUsecase interface {
	Execute(ctx context.Context, input usecase.RecordCollabOpenedInput) error
}

// PostCollabOpenedHandler decodes the event, enforces idempotency, and
// delegates to PostCollabOpenedUsecase.
type PostCollabOpenedHandler struct {
	idempotency PostCollabOpenedIdempotencyRepository
	usecase     PostCollabOpenedUsecase
	logger      *slog.Logger
}

// NewPostCollabOpenedHandler creates a PostCollabOpenedHandler.
func NewPostCollabOpenedHandler(idempotency PostCollabOpenedIdempotencyRepository, usecase PostCollabOpenedUsecase, logger *slog.Logger) *PostCollabOpenedHandler {
	return &PostCollabOpenedHandler{idempotency: idempotency, usecase: usecase, logger: logger}
}

// Handle implements dispatch.EventHandlerFunc.
func (h *PostCollabOpenedHandler) Handle(ctx context.Context, msg *message.Message) error {
	var event PostCollabOpenedEvent
	if err := json.Unmarshal(msg.Payload, &event); err != nil {
		return rmqerror.NewPermanent(fmt.Errorf("decode post collab opened event: %w", err))
	}

	if err := validatePostCollabOpenedEvent(event); err != nil {
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

	input := usecase.RecordCollabOpenedInput{
		CollabID:    event.CollabID,
		PostID:      event.PostID,
		OwnerUserID: event.OwnerUserID,
		CreatedAt:   event.CreatedAt,
	}
	if err := h.usecase.Execute(ctx, input); err != nil {
		return fmt.Errorf("execute record collab opened usecase for event %s: %w", event.ID, err)
	}

	if err := h.idempotency.MarkProcessed(ctx, event.ID, event.CorrelationID, PostCollabOpenedEventType); err != nil {
		return fmt.Errorf("mark post collab opened event %s processed: %w", event.ID, err)
	}

	return nil
}

func validatePostCollabOpenedEvent(event PostCollabOpenedEvent) error {
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
