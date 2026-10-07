package rabbitmq

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"time"

	"github.com/ThreeDotsLabs/watermill/message"
	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/post/application/usecase"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
)

// PostCollabLinkedEventType identifies this event for idempotency
// bookkeeping.
const PostCollabLinkedEventType = "CollabLinkedEvent"

// PostCollabLinkedEvent is the wire shape of CollabLinkedEvent published by
// post-command-service. Field names are camelCase, matching the real Java
// CollabLinkedEvent record. CollabLinkedEvent only carries a relational
// fact — an already-existing post (already projected into this service's
// posts collection via an earlier PostCreatedEvent) was linked into a
// collaboration. It carries no post content of its own, so the handler
// only updates the already-projected post's collab_id; it never sources or
// overwrites content fields from this event.
type PostCollabLinkedEvent struct {
	ID            uuid.UUID `json:"id"`
	CorrelationID uuid.UUID `json:"correlationId"`
	OccurredAt    time.Time `json:"occurredAt"`
	PostID        uuid.UUID `json:"postId"`
	CollabID      uuid.UUID `json:"collabId"`
}

// PostCollabLinkedIdempotencyRepository records and checks processed
// events.
type PostCollabLinkedIdempotencyRepository interface {
	IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error)
	MarkProcessed(ctx context.Context, eventID, correlationID uuid.UUID, eventType string) error
}

// PostCollabLinkedUsecase executes the collab-link projection for a post
// already projected into the read model. Implemented by
// post/application/usecase.LinkPostCollabUsecase.
type PostCollabLinkedUsecase interface {
	Execute(ctx context.Context, input usecase.LinkPostCollabInput) error
}

// PostCollabLinkedHandler decodes PostCollabLinkedEvent deliveries,
// enforces idempotency, and delegates to PostCollabLinkedUsecase.
type PostCollabLinkedHandler struct {
	idempotency PostCollabLinkedIdempotencyRepository
	usecase     PostCollabLinkedUsecase
	logger      *slog.Logger
}

// NewPostCollabLinkedHandler creates a PostCollabLinkedHandler.
func NewPostCollabLinkedHandler(idempotency PostCollabLinkedIdempotencyRepository, uc PostCollabLinkedUsecase, logger *slog.Logger) *PostCollabLinkedHandler {
	return &PostCollabLinkedHandler{idempotency: idempotency, usecase: uc, logger: logger}
}

// Handle implements dispatch.EventHandlerFunc.
func (h *PostCollabLinkedHandler) Handle(ctx context.Context, msg *message.Message) error {
	var event PostCollabLinkedEvent
	if err := json.Unmarshal(msg.Payload, &event); err != nil {
		return rmqerror.NewPermanent(fmt.Errorf("decode post collab linked event: %w", err))
	}

	if err := validatePostCollabLinkedEvent(event); err != nil {
		return rmqerror.NewPermanent(err)
	}

	alreadyProcessed, err := h.idempotency.IsProcessed(ctx, event.ID)
	if err != nil {
		return fmt.Errorf("check post collab linked event %s processed: %w", event.ID, err)
	}
	if alreadyProcessed {
		h.logger.WarnContext(ctx, "discarding duplicate post collab linked event", "event_id", event.ID)
		return nil
	}

	input := usecase.LinkPostCollabInput{
		PostID:   event.PostID,
		CollabID: event.CollabID,
	}
	if err := h.usecase.Execute(ctx, input); err != nil {
		return fmt.Errorf("execute link post collab usecase for event %s: %w", event.ID, err)
	}

	if err := h.idempotency.MarkProcessed(ctx, event.ID, event.CorrelationID, PostCollabLinkedEventType); err != nil {
		return fmt.Errorf("mark post collab linked event %s processed: %w", event.ID, err)
	}

	return nil
}

func validatePostCollabLinkedEvent(event PostCollabLinkedEvent) error {
	if event.ID == uuid.Nil {
		return fmt.Errorf("post collab linked event: id is required")
	}
	if event.CorrelationID == uuid.Nil {
		return fmt.Errorf("post collab linked event: correlationId is required")
	}
	if event.OccurredAt.IsZero() {
		return fmt.Errorf("post collab linked event: occurredAt is required")
	}
	if event.PostID == uuid.Nil {
		return fmt.Errorf("post collab linked event: postId is required")
	}
	if event.CollabID == uuid.Nil {
		return fmt.Errorf("post collab linked event: collabId is required")
	}

	return nil
}
