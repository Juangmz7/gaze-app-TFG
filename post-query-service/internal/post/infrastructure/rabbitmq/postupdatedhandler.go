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

// PostUpdatedEventType identifies this event for idempotency bookkeeping.
const PostUpdatedEventType = "PostUpdatedEvent"

// PostUpdatedEvent is the wire shape of PostUpdatedEvent published by
// post-command-service. Field names are camelCase, matching the real Java
// PostUpdatedEvent record. Media is deliberately not modeled here, matching
// the pre-existing gap in PostCreatedEvent's/CollabLinkedEvent's handlers.
type PostUpdatedEvent struct {
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

// PostUpdatedIdempotencyRepository records and checks processed events.
type PostUpdatedIdempotencyRepository interface {
	IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error)
	MarkProcessed(ctx context.Context, eventID, correlationID uuid.UUID, eventType string) error
}

// PostUpdatedUsecase executes the post update projection.
type PostUpdatedUsecase interface {
	Execute(ctx context.Context, input usecase.UpdatePostInput) error
}

// PostUpdatedHandler decodes PostUpdatedEvent deliveries, enforces
// idempotency, and delegates to PostUpdatedUsecase.
type PostUpdatedHandler struct {
	idempotency PostUpdatedIdempotencyRepository
	usecase     PostUpdatedUsecase
	logger      *slog.Logger
}

// NewPostUpdatedHandler creates a PostUpdatedHandler.
func NewPostUpdatedHandler(idempotency PostUpdatedIdempotencyRepository, uc PostUpdatedUsecase, logger *slog.Logger) *PostUpdatedHandler {
	return &PostUpdatedHandler{idempotency: idempotency, usecase: uc, logger: logger}
}

// Handle implements dispatch.EventHandlerFunc.
func (h *PostUpdatedHandler) Handle(ctx context.Context, msg *message.Message) error {
	var event PostUpdatedEvent
	if err := json.Unmarshal(msg.Payload, &event); err != nil {
		return rmqerror.NewPermanent(fmt.Errorf("decode post updated event: %w", err))
	}

	if err := validatePostUpdatedEvent(event); err != nil {
		return rmqerror.NewPermanent(err)
	}

	alreadyProcessed, err := h.idempotency.IsProcessed(ctx, event.ID)
	if err != nil {
		return fmt.Errorf("check post updated event %s processed: %w", event.ID, err)
	}
	if alreadyProcessed {
		h.logger.WarnContext(ctx, "discarding duplicate post updated event", "event_id", event.ID)
		return nil
	}

	input := usecase.UpdatePostInput{
		PostID:      event.PostID,
		PostType:    event.PostType,
		Description: event.Description,
		Tags:        event.PostTags,
		UpdatedAt:   event.UpdatedAt,
	}
	if err := h.usecase.Execute(ctx, input); err != nil {
		return fmt.Errorf("execute update post usecase for event %s: %w", event.ID, err)
	}

	if err := h.idempotency.MarkProcessed(ctx, event.ID, event.CorrelationID, PostUpdatedEventType); err != nil {
		return fmt.Errorf("mark post updated event %s processed: %w", event.ID, err)
	}

	return nil
}

func validatePostUpdatedEvent(event PostUpdatedEvent) error {
	if event.ID == uuid.Nil {
		return fmt.Errorf("post updated event: id is required")
	}
	if event.CorrelationID == uuid.Nil {
		return fmt.Errorf("post updated event: correlationId is required")
	}
	if event.OccurredAt.IsZero() {
		return fmt.Errorf("post updated event: occurredAt is required")
	}
	if event.PostID == uuid.Nil {
		return fmt.Errorf("post updated event: postId is required")
	}
	if event.UserID == uuid.Nil {
		return fmt.Errorf("post updated event: userId is required")
	}

	return nil
}
