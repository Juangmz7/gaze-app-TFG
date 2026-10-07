// Package rabbitmq handles PostShareCreatedEvent and PostShareDeletedEvent
// deliveries from topology.ExchangePostEvents (routing keys
// rk.post.share.created and rk.post.share.deleted).
package rabbitmq

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"time"

	"github.com/ThreeDotsLabs/watermill/message"
	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/share/application/usecase"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
)

// PostShareCreatedEventType identifies this event for idempotency
// bookkeeping.
const PostShareCreatedEventType = "PostShareCreatedEvent"

// PostShareCreatedEvent is the wire shape of PostShareCreatedEvent
// published by post-command-service.
type PostShareCreatedEvent struct {
	ID            uuid.UUID `json:"id"`
	CorrelationID uuid.UUID `json:"correlation_id"`
	OccurredAt    time.Time `json:"occurred_at"`
	ShareID       uuid.UUID `json:"share_id"`
	PostID        uuid.UUID `json:"post_id"`
	UserID        uuid.UUID `json:"user_id"`
	CreatedAt     time.Time `json:"created_at"`
}

// PostShareCreatedIdempotencyRepository records and checks processed
// events.
type PostShareCreatedIdempotencyRepository interface {
	IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error)
	MarkProcessed(ctx context.Context, eventID, correlationID uuid.UUID, eventType string) error
}

// PostShareCreatedUsecase executes the share projection.
type PostShareCreatedUsecase interface {
	Execute(ctx context.Context, input usecase.RecordShareInput) error
}

// PostShareCreatedHandler decodes PostShareCreatedEvent deliveries,
// enforces idempotency, and delegates to PostShareCreatedUsecase.
type PostShareCreatedHandler struct {
	idempotency PostShareCreatedIdempotencyRepository
	usecase     PostShareCreatedUsecase
	logger      *slog.Logger
}

// NewPostShareCreatedHandler creates a PostShareCreatedHandler.
func NewPostShareCreatedHandler(idempotency PostShareCreatedIdempotencyRepository, usecase PostShareCreatedUsecase, logger *slog.Logger) *PostShareCreatedHandler {
	return &PostShareCreatedHandler{idempotency: idempotency, usecase: usecase, logger: logger}
}

// Handle implements dispatch.EventHandlerFunc.
func (h *PostShareCreatedHandler) Handle(ctx context.Context, msg *message.Message) error {
	var event PostShareCreatedEvent
	if err := json.Unmarshal(msg.Payload, &event); err != nil {
		return rmqerror.NewPermanent(fmt.Errorf("decode post share created event: %w", err))
	}

	if err := validatePostShareCreatedEvent(event); err != nil {
		return rmqerror.NewPermanent(err)
	}

	alreadyProcessed, err := h.idempotency.IsProcessed(ctx, event.ID)
	if err != nil {
		return fmt.Errorf("check post share created event %s processed: %w", event.ID, err)
	}
	if alreadyProcessed {
		h.logger.WarnContext(ctx, "discarding duplicate post share created event", "event_id", event.ID)
		return nil
	}

	input := usecase.RecordShareInput{
		ShareID:   event.ShareID,
		PostID:    event.PostID,
		UserID:    event.UserID,
		CreatedAt: event.CreatedAt,
	}
	if err := h.usecase.Execute(ctx, input); err != nil {
		return fmt.Errorf("execute record share usecase for event %s: %w", event.ID, err)
	}

	if err := h.idempotency.MarkProcessed(ctx, event.ID, event.CorrelationID, PostShareCreatedEventType); err != nil {
		return fmt.Errorf("mark post share created event %s processed: %w", event.ID, err)
	}

	return nil
}

func validatePostShareCreatedEvent(event PostShareCreatedEvent) error {
	if event.ID == uuid.Nil {
		return fmt.Errorf("post share created event: id is required")
	}
	if event.CorrelationID == uuid.Nil {
		return fmt.Errorf("post share created event: correlation_id is required")
	}
	if event.OccurredAt.IsZero() {
		return fmt.Errorf("post share created event: occurred_at is required")
	}
	if event.ShareID == uuid.Nil {
		return fmt.Errorf("post share created event: share_id is required")
	}
	if event.PostID == uuid.Nil {
		return fmt.Errorf("post share created event: post_id is required")
	}
	if event.UserID == uuid.Nil {
		return fmt.Errorf("post share created event: user_id is required")
	}

	return nil
}
