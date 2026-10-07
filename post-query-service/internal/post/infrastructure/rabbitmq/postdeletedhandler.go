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

// PostDeletedEventType identifies this event for idempotency bookkeeping.
const PostDeletedEventType = "PostDeletedEvent"

// PostDeletedEvent is the wire shape of PostDeletedEvent published by
// post-command-service. Field names are camelCase, matching the real Java
// PostDeletedEvent record.
type PostDeletedEvent struct {
	ID            uuid.UUID `json:"id"`
	CorrelationID uuid.UUID `json:"correlationId"`
	PostID        uuid.UUID `json:"postId"`
	UserID        uuid.UUID `json:"userId"`
	OccurredAt    time.Time `json:"occurredAt"`
}

// PostDeletedIdempotencyRepository records and checks processed events.
type PostDeletedIdempotencyRepository interface {
	IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error)
	MarkProcessed(ctx context.Context, eventID, correlationID uuid.UUID, eventType string) error
}

// PostDeletedUsecase executes the post deletion projection.
type PostDeletedUsecase interface {
	Execute(ctx context.Context, input usecase.DeletePostInput) error
}

// PostDeletedHandler decodes PostDeletedEvent deliveries, enforces
// idempotency, and delegates to PostDeletedUsecase.
type PostDeletedHandler struct {
	idempotency PostDeletedIdempotencyRepository
	usecase     PostDeletedUsecase
	logger      *slog.Logger
}

// NewPostDeletedHandler creates a PostDeletedHandler.
func NewPostDeletedHandler(idempotency PostDeletedIdempotencyRepository, uc PostDeletedUsecase, logger *slog.Logger) *PostDeletedHandler {
	return &PostDeletedHandler{idempotency: idempotency, usecase: uc, logger: logger}
}

// Handle implements dispatch.EventHandlerFunc.
func (h *PostDeletedHandler) Handle(ctx context.Context, msg *message.Message) error {
	var event PostDeletedEvent
	if err := json.Unmarshal(msg.Payload, &event); err != nil {
		return rmqerror.NewPermanent(fmt.Errorf("decode post deleted event: %w", err))
	}

	if err := validatePostDeletedEvent(event); err != nil {
		return rmqerror.NewPermanent(err)
	}

	alreadyProcessed, err := h.idempotency.IsProcessed(ctx, event.ID)
	if err != nil {
		return fmt.Errorf("check post deleted event %s processed: %w", event.ID, err)
	}
	if alreadyProcessed {
		h.logger.WarnContext(ctx, "discarding duplicate post deleted event", "event_id", event.ID)
		return nil
	}

	input := usecase.DeletePostInput{PostID: event.PostID}
	if err := h.usecase.Execute(ctx, input); err != nil {
		return fmt.Errorf("execute delete post usecase for event %s: %w", event.ID, err)
	}

	if err := h.idempotency.MarkProcessed(ctx, event.ID, event.CorrelationID, PostDeletedEventType); err != nil {
		return fmt.Errorf("mark post deleted event %s processed: %w", event.ID, err)
	}

	return nil
}

func validatePostDeletedEvent(event PostDeletedEvent) error {
	if event.ID == uuid.Nil {
		return fmt.Errorf("post deleted event: id is required")
	}
	if event.CorrelationID == uuid.Nil {
		return fmt.Errorf("post deleted event: correlationId is required")
	}
	if event.OccurredAt.IsZero() {
		return fmt.Errorf("post deleted event: occurredAt is required")
	}
	if event.PostID == uuid.Nil {
		return fmt.Errorf("post deleted event: postId is required")
	}
	if event.UserID == uuid.Nil {
		return fmt.Errorf("post deleted event: userId is required")
	}

	return nil
}
