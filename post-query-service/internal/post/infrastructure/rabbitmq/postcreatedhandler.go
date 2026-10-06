// Package rabbitmq handles post domain event deliveries from
// topology.ExchangePostEvents. This file handles only PostCreatedEvent
// (routing key rk.post.created); CollabLinkedEvent (rk.post.collab.linked)
// has its own handler in postcollablinkedhandler.go.
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

// PostCreatedEventType identifies this event for idempotency bookkeeping.
const PostCreatedEventType = "PostCreatedEvent"

// PostCreatedEvent is the wire shape of PostCreatedEvent published by
// post-command-service.
type PostCreatedEvent struct {
	ID            uuid.UUID `json:"id"`
	CorrelationID uuid.UUID `json:"correlation_id"`
	OccurredAt    time.Time `json:"occurred_at"`
	PostID        uuid.UUID `json:"post_id"`
	UserID        uuid.UUID `json:"user_id"`
	CollabID      uuid.UUID `json:"collab_id"`
	PostType      string    `json:"post_type"`
	Description   string    `json:"description"`
	PostTags      []string  `json:"post_tags"`
	CreatedAt     time.Time `json:"created_at"`
	UpdatedAt     time.Time `json:"updated_at"`
}

// PostCreatedIdempotencyRepository records and checks processed events.
// Implemented by shared/infrastructure/rabbitmq/idempotency.Repository.
type PostCreatedIdempotencyRepository interface {
	IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error)
	MarkProcessed(ctx context.Context, eventID, correlationID uuid.UUID, eventType string) error
}

// PostCreatedUsecase executes the post creation projection. Implemented by
// post/application/usecase.Usecase.
type PostCreatedUsecase interface {
	Execute(ctx context.Context, input usecase.CreatePostInput) error
}

// PostCreatedHandler decodes PostCreatedEvent deliveries, enforces
// idempotency, and delegates to PostCreatedUsecase.
type PostCreatedHandler struct {
	idempotency PostCreatedIdempotencyRepository
	usecase     PostCreatedUsecase
	logger      *slog.Logger
}

// NewPostCreatedHandler creates a PostCreatedHandler.
func NewPostCreatedHandler(idempotency PostCreatedIdempotencyRepository, usecase PostCreatedUsecase, logger *slog.Logger) *PostCreatedHandler {
	return &PostCreatedHandler{idempotency: idempotency, usecase: usecase, logger: logger}
}

// Handle implements dispatch.EventHandlerFunc.
func (h *PostCreatedHandler) Handle(ctx context.Context, msg *message.Message) error {
	var event PostCreatedEvent
	if err := json.Unmarshal(msg.Payload, &event); err != nil {
		return rmqerror.NewPermanent(fmt.Errorf("decode post created event: %w", err))
	}

	if err := validatePostCreatedEvent(event); err != nil {
		return rmqerror.NewPermanent(err)
	}

	alreadyProcessed, err := h.idempotency.IsProcessed(ctx, event.ID)
	if err != nil {
		return fmt.Errorf("check post created event %s processed: %w", event.ID, err)
	}
	if alreadyProcessed {
		h.logger.WarnContext(ctx, "discarding duplicate post created event", "event_id", event.ID)
		return nil
	}

	input := usecase.CreatePostInput{
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

	if err := h.idempotency.MarkProcessed(ctx, event.ID, event.CorrelationID, PostCreatedEventType); err != nil {
		return fmt.Errorf("mark post created event %s processed: %w", event.ID, err)
	}

	return nil
}

func validatePostCreatedEvent(event PostCreatedEvent) error {
	if event.ID == uuid.Nil {
		return fmt.Errorf("post created event: id is required")
	}
	if event.CorrelationID == uuid.Nil {
		return fmt.Errorf("post created event: correlation_id is required")
	}
	if event.OccurredAt.IsZero() {
		return fmt.Errorf("post created event: occurred_at is required")
	}
	if event.PostID == uuid.Nil {
		return fmt.Errorf("post created event: post_id is required")
	}
	if event.UserID == uuid.Nil {
		return fmt.Errorf("post created event: user_id is required")
	}

	return nil
}
