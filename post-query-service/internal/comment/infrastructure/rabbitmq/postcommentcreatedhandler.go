// Package rabbitmq handles PostCommentCreatedEvent, CommentUpdatedEvent,
// and CommentDeletedEvent deliveries from topology.ExchangePostEvents
// (routing keys rk.post.comment.created, rk.post.comment.updated,
// rk.post.comment.deleted).
package rabbitmq

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"time"

	"github.com/ThreeDotsLabs/watermill/message"
	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/comment/application/usecase"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
)

// PostCommentCreatedEventType identifies this event for idempotency
// bookkeeping.
const PostCommentCreatedEventType = "PostCommentCreatedEvent"

// PostCommentCreatedEvent is the wire shape of PostCommentCreatedEvent
// published by post-command-service.
type PostCommentCreatedEvent struct {
	ID            uuid.UUID `json:"id"`
	CorrelationID uuid.UUID `json:"correlation_id"`
	OccurredAt    time.Time `json:"occurred_at"`
	CommentID     uuid.UUID `json:"comment_id"`
	PostID        uuid.UUID `json:"post_id"`
	UserID        uuid.UUID `json:"user_id"`
	Content       string    `json:"content"`
	CreatedAt     time.Time `json:"created_at"`
}

// PostCommentCreatedIdempotencyRepository records and checks processed
// events.
type PostCommentCreatedIdempotencyRepository interface {
	IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error)
	MarkProcessed(ctx context.Context, eventID, correlationID uuid.UUID, eventType string) error
}

// PostCommentCreatedUsecase executes the comment creation projection.
type PostCommentCreatedUsecase interface {
	Execute(ctx context.Context, input usecase.RecordCommentInput) error
}

// PostCommentCreatedHandler decodes PostCommentCreatedEvent deliveries,
// enforces idempotency, and delegates to PostCommentCreatedUsecase.
type PostCommentCreatedHandler struct {
	idempotency PostCommentCreatedIdempotencyRepository
	usecase     PostCommentCreatedUsecase
	logger      *slog.Logger
}

// NewPostCommentCreatedHandler creates a PostCommentCreatedHandler.
func NewPostCommentCreatedHandler(idempotency PostCommentCreatedIdempotencyRepository, uc PostCommentCreatedUsecase, logger *slog.Logger) *PostCommentCreatedHandler {
	return &PostCommentCreatedHandler{idempotency: idempotency, usecase: uc, logger: logger}
}

// Handle implements dispatch.EventHandlerFunc.
func (h *PostCommentCreatedHandler) Handle(ctx context.Context, msg *message.Message) error {
	var event PostCommentCreatedEvent
	if err := json.Unmarshal(msg.Payload, &event); err != nil {
		return rmqerror.NewPermanent(fmt.Errorf("decode post comment created event: %w", err))
	}

	if err := validatePostCommentCreatedEvent(event); err != nil {
		return rmqerror.NewPermanent(err)
	}

	alreadyProcessed, err := h.idempotency.IsProcessed(ctx, event.ID)
	if err != nil {
		return fmt.Errorf("check post comment created event %s processed: %w", event.ID, err)
	}
	if alreadyProcessed {
		h.logger.WarnContext(ctx, "discarding duplicate post comment created event", "event_id", event.ID)
		return nil
	}

	input := usecase.RecordCommentInput{
		CommentID: event.CommentID,
		PostID:    event.PostID,
		UserID:    event.UserID,
		Content:   event.Content,
		CreatedAt: event.CreatedAt,
	}
	if err := h.usecase.Execute(ctx, input); err != nil {
		return fmt.Errorf("execute record comment usecase for event %s: %w", event.ID, err)
	}

	if err := h.idempotency.MarkProcessed(ctx, event.ID, event.CorrelationID, PostCommentCreatedEventType); err != nil {
		return fmt.Errorf("mark post comment created event %s processed: %w", event.ID, err)
	}

	return nil
}

func validatePostCommentCreatedEvent(event PostCommentCreatedEvent) error {
	if event.ID == uuid.Nil {
		return fmt.Errorf("post comment created event: id is required")
	}
	if event.CorrelationID == uuid.Nil {
		return fmt.Errorf("post comment created event: correlation_id is required")
	}
	if event.OccurredAt.IsZero() {
		return fmt.Errorf("post comment created event: occurred_at is required")
	}
	if event.CommentID == uuid.Nil {
		return fmt.Errorf("post comment created event: comment_id is required")
	}
	if event.PostID == uuid.Nil {
		return fmt.Errorf("post comment created event: post_id is required")
	}
	if event.UserID == uuid.Nil {
		return fmt.Errorf("post comment created event: user_id is required")
	}

	return nil
}
