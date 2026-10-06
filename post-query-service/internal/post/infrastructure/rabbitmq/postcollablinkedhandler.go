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
// CollabLinkedEvent record. CollabLinkedEvent is semantically a new post
// being linked into an existing collaboration, so its payload shape is the
// post entity, not the collab entity; media is deliberately not modeled
// here, matching the pre-existing gap in PostCreatedEvent's handler.
type PostCollabLinkedEvent struct {
	ID            uuid.UUID `json:"id"`
	CorrelationID uuid.UUID `json:"correlationId"`
	OccurredAt    time.Time `json:"occurredAt"`
	PostID        uuid.UUID `json:"postId"`
	UserID        uuid.UUID `json:"userId"`
	CollabID      uuid.UUID `json:"collabId"`
	PostType      string    `json:"postType"`
	Description   string    `json:"description"`
	PostTags      []string  `json:"postTags"`
	CreatedAt     time.Time `json:"createdAt"`
	UpdatedAt     time.Time `json:"updatedAt"`
}

// PostCollabLinkedIdempotencyRepository records and checks processed
// events.
type PostCollabLinkedIdempotencyRepository interface {
	IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error)
	MarkProcessed(ctx context.Context, eventID, correlationID uuid.UUID, eventType string) error
}

// PostCollabLinkedUsecase executes the post projection for a post linked
// into a collaboration. It is the same post/application/usecase.Usecase
// used by PostCreatedHandler.
type PostCollabLinkedUsecase interface {
	Execute(ctx context.Context, input usecase.Input) error
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

	input := usecase.Input{
		PostID:      event.PostID,
		UserID:      event.UserID,
		CollabID:    event.CollabID,
		PostType:    event.PostType,
		Description: event.Description,
		Tags:        event.PostTags,
		CreatedAt:   event.CreatedAt,
		UpdatedAt:   event.UpdatedAt,
	}
	if err := h.usecase.Execute(ctx, input); err != nil {
		return fmt.Errorf("execute create post usecase for event %s: %w", event.ID, err)
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
	if event.UserID == uuid.Nil {
		return fmt.Errorf("post collab linked event: userId is required")
	}
	if event.CollabID == uuid.Nil {
		return fmt.Errorf("post collab linked event: collabId is required")
	}

	return nil
}
