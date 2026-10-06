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

// PostLikeDeletedEventType identifies this event for idempotency
// bookkeeping.
const PostLikeDeletedEventType = "PostLikeDeletedEvent"

// PostLikeDeletedEvent is the wire shape of PostLikeDeletedEvent published
// by post-command-service. Field names are camelCase, matching the real
// Java PostLikeDeletedEvent record. Note there is no likeId field at all;
// see usecase.DeleteLikeInput's doc comment for how this handler resolves
// which like document to remove.
type PostLikeDeletedEvent struct {
	ID            uuid.UUID `json:"id"`
	CorrelationID uuid.UUID `json:"correlationId"`
	OccurredAt    time.Time `json:"occurredAt"`
	PostID        uuid.UUID `json:"postId"`
	UserID        uuid.UUID `json:"userId"`
	Source        string    `json:"source"`
	FeedPosition  int       `json:"feedPosition"`
}

// PostLikeDeletedIdempotencyRepository records and checks processed events.
type PostLikeDeletedIdempotencyRepository interface {
	IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error)
	MarkProcessed(ctx context.Context, eventID, correlationID uuid.UUID, eventType string) error
}

// PostLikeDeletedUsecase executes the like deletion projection.
type PostLikeDeletedUsecase interface {
	Execute(ctx context.Context, input usecase.DeleteLikeInput) error
}

// PostLikeDeletedHandler decodes PostLikeDeletedEvent deliveries, enforces
// idempotency, and delegates to PostLikeDeletedUsecase.
type PostLikeDeletedHandler struct {
	idempotency PostLikeDeletedIdempotencyRepository
	usecase     PostLikeDeletedUsecase
	logger      *slog.Logger
}

// NewPostLikeDeletedHandler creates a PostLikeDeletedHandler.
func NewPostLikeDeletedHandler(idempotency PostLikeDeletedIdempotencyRepository, uc PostLikeDeletedUsecase, logger *slog.Logger) *PostLikeDeletedHandler {
	return &PostLikeDeletedHandler{idempotency: idempotency, usecase: uc, logger: logger}
}

// Handle implements dispatch.EventHandlerFunc.
func (h *PostLikeDeletedHandler) Handle(ctx context.Context, msg *message.Message) error {
	var event PostLikeDeletedEvent
	if err := json.Unmarshal(msg.Payload, &event); err != nil {
		return rmqerror.NewPermanent(fmt.Errorf("decode post like deleted event: %w", err))
	}

	if err := validatePostLikeDeletedEvent(event); err != nil {
		return rmqerror.NewPermanent(err)
	}

	alreadyProcessed, err := h.idempotency.IsProcessed(ctx, event.ID)
	if err != nil {
		return fmt.Errorf("check post like deleted event %s processed: %w", event.ID, err)
	}
	if alreadyProcessed {
		h.logger.WarnContext(ctx, "discarding duplicate post like deleted event", "event_id", event.ID)
		return nil
	}

	input := usecase.DeleteLikeInput{PostID: event.PostID, UserID: event.UserID}
	if err := h.usecase.Execute(ctx, input); err != nil {
		return fmt.Errorf("execute delete like usecase for event %s: %w", event.ID, err)
	}

	if err := h.idempotency.MarkProcessed(ctx, event.ID, event.CorrelationID, PostLikeDeletedEventType); err != nil {
		return fmt.Errorf("mark post like deleted event %s processed: %w", event.ID, err)
	}

	return nil
}

func validatePostLikeDeletedEvent(event PostLikeDeletedEvent) error {
	if event.ID == uuid.Nil {
		return fmt.Errorf("post like deleted event: id is required")
	}
	if event.CorrelationID == uuid.Nil {
		return fmt.Errorf("post like deleted event: correlationId is required")
	}
	if event.OccurredAt.IsZero() {
		return fmt.Errorf("post like deleted event: occurredAt is required")
	}
	if event.PostID == uuid.Nil {
		return fmt.Errorf("post like deleted event: postId is required")
	}
	if event.UserID == uuid.Nil {
		return fmt.Errorf("post like deleted event: userId is required")
	}

	return nil
}
