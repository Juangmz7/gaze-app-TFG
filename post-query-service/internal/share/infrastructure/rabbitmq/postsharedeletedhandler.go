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

// PostShareDeletedEventType identifies this event for idempotency
// bookkeeping.
const PostShareDeletedEventType = "PostShareDeletedEvent"

// PostShareDeletedEvent is the wire shape of PostShareDeletedEvent
// published by post-command-service. Field names are camelCase, matching
// the real Java PostShareDeletedEvent record. Note there is no shareId
// field at all; see usecase.DeleteShareInput's doc comment for how this
// handler resolves which share document to remove.
type PostShareDeletedEvent struct {
	ID            uuid.UUID `json:"id"`
	CorrelationID uuid.UUID `json:"correlationId"`
	OccurredAt    time.Time `json:"occurredAt"`
	PostID        uuid.UUID `json:"postId"`
	UserID        uuid.UUID `json:"userId"`
}

// PostShareDeletedIdempotencyRepository records and checks processed
// events.
type PostShareDeletedIdempotencyRepository interface {
	IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error)
	MarkProcessed(ctx context.Context, eventID, correlationID uuid.UUID, eventType string) error
}

// PostShareDeletedUsecase executes the share deletion projection.
type PostShareDeletedUsecase interface {
	Execute(ctx context.Context, input usecase.DeleteShareInput) error
}

// PostShareDeletedHandler decodes PostShareDeletedEvent deliveries,
// enforces idempotency, and delegates to PostShareDeletedUsecase.
type PostShareDeletedHandler struct {
	idempotency PostShareDeletedIdempotencyRepository
	usecase     PostShareDeletedUsecase
	logger      *slog.Logger
}

// NewPostShareDeletedHandler creates a PostShareDeletedHandler.
func NewPostShareDeletedHandler(idempotency PostShareDeletedIdempotencyRepository, uc PostShareDeletedUsecase, logger *slog.Logger) *PostShareDeletedHandler {
	return &PostShareDeletedHandler{idempotency: idempotency, usecase: uc, logger: logger}
}

// Handle implements dispatch.EventHandlerFunc.
func (h *PostShareDeletedHandler) Handle(ctx context.Context, msg *message.Message) error {
	var event PostShareDeletedEvent
	if err := json.Unmarshal(msg.Payload, &event); err != nil {
		return rmqerror.NewPermanent(fmt.Errorf("decode post share deleted event: %w", err))
	}

	if err := validatePostShareDeletedEvent(event); err != nil {
		return rmqerror.NewPermanent(err)
	}

	alreadyProcessed, err := h.idempotency.IsProcessed(ctx, event.ID)
	if err != nil {
		return fmt.Errorf("check post share deleted event %s processed: %w", event.ID, err)
	}
	if alreadyProcessed {
		h.logger.WarnContext(ctx, "discarding duplicate post share deleted event", "event_id", event.ID)
		return nil
	}

	input := usecase.DeleteShareInput{PostID: event.PostID, UserID: event.UserID}
	if err := h.usecase.Execute(ctx, input); err != nil {
		return fmt.Errorf("execute delete share usecase for event %s: %w", event.ID, err)
	}

	if err := h.idempotency.MarkProcessed(ctx, event.ID, event.CorrelationID, PostShareDeletedEventType); err != nil {
		return fmt.Errorf("mark post share deleted event %s processed: %w", event.ID, err)
	}

	return nil
}

func validatePostShareDeletedEvent(event PostShareDeletedEvent) error {
	if event.ID == uuid.Nil {
		return fmt.Errorf("post share deleted event: id is required")
	}
	if event.CorrelationID == uuid.Nil {
		return fmt.Errorf("post share deleted event: correlationId is required")
	}
	if event.OccurredAt.IsZero() {
		return fmt.Errorf("post share deleted event: occurredAt is required")
	}
	if event.PostID == uuid.Nil {
		return fmt.Errorf("post share deleted event: postId is required")
	}
	if event.UserID == uuid.Nil {
		return fmt.Errorf("post share deleted event: userId is required")
	}

	return nil
}
