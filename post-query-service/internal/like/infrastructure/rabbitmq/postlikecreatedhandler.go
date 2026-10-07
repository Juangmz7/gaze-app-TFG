// Package rabbitmq handles PostLikeCreatedEvent and PostLikeDeletedEvent
// deliveries from topology.ExchangePostEvents (routing keys
// rk.post.like.created and rk.post.like.deleted).
package rabbitmq

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"time"

	"github.com/ThreeDotsLabs/watermill/message"
	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/like/application/usecase"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
)

// PostLikeCreatedEventType identifies this event for idempotency
// bookkeeping.
const PostLikeCreatedEventType = "PostLikeCreatedEvent"

// PostLikeCreatedEvent is the wire shape of PostLikeCreatedEvent published
// by post-command-service.
type PostLikeCreatedEvent struct {
	ID            uuid.UUID `json:"id"`
	CorrelationID uuid.UUID `json:"correlation_id"`
	OccurredAt    time.Time `json:"occurred_at"`
	LikeID        uuid.UUID `json:"like_id"`
	PostID        uuid.UUID `json:"post_id"`
	UserID        uuid.UUID `json:"user_id"`
	CreatedAt     time.Time `json:"created_at"`
}

// PostLikeCreatedIdempotencyRepository records and checks processed events.
type PostLikeCreatedIdempotencyRepository interface {
	IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error)
	MarkProcessed(ctx context.Context, eventID, correlationID uuid.UUID, eventType string) error
}

// PostLikeCreatedUsecase executes the like projection.
type PostLikeCreatedUsecase interface {
	Execute(ctx context.Context, input usecase.RecordLikeInput) error
}

// PostLikeCreatedHandler decodes PostLikeCreatedEvent deliveries, enforces
// idempotency, and delegates to PostLikeCreatedUsecase.
type PostLikeCreatedHandler struct {
	idempotency PostLikeCreatedIdempotencyRepository
	usecase     PostLikeCreatedUsecase
	logger      *slog.Logger
}

// NewPostLikeCreatedHandler creates a PostLikeCreatedHandler.
func NewPostLikeCreatedHandler(idempotency PostLikeCreatedIdempotencyRepository, usecase PostLikeCreatedUsecase, logger *slog.Logger) *PostLikeCreatedHandler {
	return &PostLikeCreatedHandler{idempotency: idempotency, usecase: usecase, logger: logger}
}

// Handle implements dispatch.EventHandlerFunc.
func (h *PostLikeCreatedHandler) Handle(ctx context.Context, msg *message.Message) error {
	var event PostLikeCreatedEvent
	if err := json.Unmarshal(msg.Payload, &event); err != nil {
		return rmqerror.NewPermanent(fmt.Errorf("decode post like created event: %w", err))
	}

	if err := validatePostLikeCreatedEvent(event); err != nil {
		return rmqerror.NewPermanent(err)
	}

	alreadyProcessed, err := h.idempotency.IsProcessed(ctx, event.ID)
	if err != nil {
		return fmt.Errorf("check post like created event %s processed: %w", event.ID, err)
	}
	if alreadyProcessed {
		h.logger.WarnContext(ctx, "discarding duplicate post like created event", "event_id", event.ID)
		return nil
	}

	input := usecase.RecordLikeInput{
		LikeID:    event.LikeID,
		PostID:    event.PostID,
		UserID:    event.UserID,
		CreatedAt: event.CreatedAt,
	}
	if err := h.usecase.Execute(ctx, input); err != nil {
		return fmt.Errorf("execute record like usecase for event %s: %w", event.ID, err)
	}

	if err := h.idempotency.MarkProcessed(ctx, event.ID, event.CorrelationID, PostLikeCreatedEventType); err != nil {
		return fmt.Errorf("mark post like created event %s processed: %w", event.ID, err)
	}

	return nil
}

func validatePostLikeCreatedEvent(event PostLikeCreatedEvent) error {
	if event.ID == uuid.Nil {
		return fmt.Errorf("post like created event: id is required")
	}
	if event.CorrelationID == uuid.Nil {
		return fmt.Errorf("post like created event: correlation_id is required")
	}
	if event.OccurredAt.IsZero() {
		return fmt.Errorf("post like created event: occurred_at is required")
	}
	if event.LikeID == uuid.Nil {
		return fmt.Errorf("post like created event: like_id is required")
	}
	if event.PostID == uuid.Nil {
		return fmt.Errorf("post like created event: post_id is required")
	}
	if event.UserID == uuid.Nil {
		return fmt.Errorf("post like created event: user_id is required")
	}

	return nil
}
