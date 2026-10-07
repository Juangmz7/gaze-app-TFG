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

// CommentUpdatedEvent is the wire shape of CommentUpdatedEvent published by
// post-command-service. Field names are camelCase, matching the real Java
// CommentUpdatedEvent record.
//
// CommentUpdatedEvent is, verified against
// post-command-service/.../comment/infrastructure/events/CommentUpdatedEvent.java,
// the one Java domain event in this codebase that does not implement
// EventMessage: it has no id, correlationId, or occurredAt fields at all.
// CommentUpdatedHandler therefore cannot run the usual
// IsProcessed/MarkProcessed-by-event-id idempotency gate (there is no event
// id to key on, and fabricating one would defeat the point of the check).
// See CommentUpdatedHandler's doc comment and
// usecase.UpdateCommentUsecase's doc comment for the compensating rule this
// forces; a future task should fix post-command-service to make
// CommentUpdatedEvent implement EventMessage like every other event.
type CommentUpdatedEvent struct {
	CommentID uuid.UUID  `json:"commentId"`
	PostID    uuid.UUID  `json:"postId"`
	UserID    uuid.UUID  `json:"userId"`
	Content   string     `json:"content"`
	ReplyTo   *uuid.UUID `json:"replyTo"`
	CreatedAt time.Time  `json:"createdAt"`
	UpdatedAt time.Time  `json:"updatedAt"`
}

// CommentUpdatedUsecase executes the comment update projection.
type CommentUpdatedUsecase interface {
	Execute(ctx context.Context, input usecase.UpdateCommentInput) error
}

// CommentUpdatedHandler decodes CommentUpdatedEvent deliveries and
// delegates to CommentUpdatedUsecase.
//
// Unlike every other handler in this service, CommentUpdatedHandler does
// not check an idempotency repository before calling its usecase: the real
// Java CommentUpdatedEvent carries no event id to key that check on (see
// CommentUpdatedEvent's doc comment). Correctness instead relies entirely
// on usecase.UpdateCommentUsecase's version-gated Update plus its
// version-conflict safety net; this is a deliberate, documented compensating
// rule forced by an upstream Java bug, not an oversight.
type CommentUpdatedHandler struct {
	usecase CommentUpdatedUsecase
	logger  *slog.Logger
}

// NewCommentUpdatedHandler creates a CommentUpdatedHandler.
func NewCommentUpdatedHandler(uc CommentUpdatedUsecase, logger *slog.Logger) *CommentUpdatedHandler {
	return &CommentUpdatedHandler{usecase: uc, logger: logger}
}

// Handle implements dispatch.EventHandlerFunc.
func (h *CommentUpdatedHandler) Handle(ctx context.Context, msg *message.Message) error {
	var event CommentUpdatedEvent
	if err := json.Unmarshal(msg.Payload, &event); err != nil {
		return rmqerror.NewPermanent(fmt.Errorf("decode comment updated event: %w", err))
	}

	if err := validateCommentUpdatedEvent(event); err != nil {
		return rmqerror.NewPermanent(err)
	}

	input := usecase.UpdateCommentInput{
		CommentID: event.CommentID,
		PostID:    event.PostID,
		UserID:    event.UserID,
		Content:   event.Content,
		UpdatedAt: event.UpdatedAt,
	}
	if err := h.usecase.Execute(ctx, input); err != nil {
		return fmt.Errorf("execute update comment usecase for comment %s: %w", event.CommentID, err)
	}

	return nil
}

func validateCommentUpdatedEvent(event CommentUpdatedEvent) error {
	if event.CommentID == uuid.Nil {
		return fmt.Errorf("comment updated event: commentId is required")
	}
	if event.PostID == uuid.Nil {
		return fmt.Errorf("comment updated event: postId is required")
	}
	if event.UserID == uuid.Nil {
		return fmt.Errorf("comment updated event: userId is required")
	}
	if event.UpdatedAt.IsZero() {
		return fmt.Errorf("comment updated event: updatedAt is required")
	}

	return nil
}
