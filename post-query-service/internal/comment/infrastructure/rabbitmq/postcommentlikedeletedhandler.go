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
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/eventlog"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
)

// PostCommentLikeDeletedEventType identifies this event for idempotency bookkeeping.
const PostCommentLikeDeletedEventType = "PostCommentLikeDeletedEvent"

// PostCommentLikeDeletedEvent is the wire shape of PostCommentLikeDeletedEvent
// published by post-command-service on rk.post.comment.like.deleted. Field names are
// camelCase, matching the real Java record, which has no postId.
type PostCommentLikeDeletedEvent struct {
	ID            uuid.UUID `json:"id"`
	CorrelationID uuid.UUID `json:"correlationId"`
	OccurredAt    time.Time `json:"occurredAt"`
	CommentID     uuid.UUID `json:"commentId"`
	UserID        uuid.UUID `json:"userId"`
	Source        string    `json:"source"`
	FeedPosition  int       `json:"feedPosition"`
}

// PostCommentLikeDeletedIdempotencyRepository records and checks processed events.
type PostCommentLikeDeletedIdempotencyRepository interface {
	IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error)
	MarkProcessed(ctx context.Context, eventID, correlationID uuid.UUID, eventType string) error
}

// PostCommentLikeDeletedTransactor runs its function inside one MongoDB transaction.
// Implemented by shared/infrastructure/database.Transactor.
type PostCommentLikeDeletedTransactor interface {
	WithTransaction(ctx context.Context, fn func(ctx context.Context) error) error
}

// PostCommentLikeDeletedUsecase executes the comment like deletion projection.
type PostCommentLikeDeletedUsecase interface {
	Execute(ctx context.Context, input usecase.DeleteCommentLikeInput) error
}

// PostCommentLikeDeletedHandler decodes PostCommentLikeDeletedEvent deliveries,
// enforces idempotency, and delegates to PostCommentLikeDeletedUsecase.
type PostCommentLikeDeletedHandler struct {
	transactor  PostCommentLikeDeletedTransactor
	idempotency PostCommentLikeDeletedIdempotencyRepository
	usecase     PostCommentLikeDeletedUsecase
	logger      *slog.Logger
}

// NewPostCommentLikeDeletedHandler creates a PostCommentLikeDeletedHandler.
func NewPostCommentLikeDeletedHandler(transactor PostCommentLikeDeletedTransactor, idempotency PostCommentLikeDeletedIdempotencyRepository, uc PostCommentLikeDeletedUsecase, logger *slog.Logger) *PostCommentLikeDeletedHandler {
	return &PostCommentLikeDeletedHandler{transactor: transactor, idempotency: idempotency, usecase: uc, logger: logger}
}

// Handle implements dispatch.EventHandlerFunc.
func (h *PostCommentLikeDeletedHandler) Handle(ctx context.Context, msg *message.Message) error {
	var event PostCommentLikeDeletedEvent
	if err := json.Unmarshal(msg.Payload, &event); err != nil {
		eventlog.WarnInvalid(ctx, h.logger, PostCommentLikeDeletedEventType, msg.Payload, err)
		return rmqerror.NewPermanent(fmt.Errorf("decode post comment like deleted event: %w", err))
	}

	if err := validatePostCommentLikeDeletedEvent(event); err != nil {
		eventlog.WarnInvalid(ctx, h.logger, PostCommentLikeDeletedEventType, msg.Payload, err)
		return rmqerror.NewPermanent(err)
	}

	// IsProcessed, the projection (including the comment's like counter) and
	// MarkProcessed commit or abort together. The driver may re-run this function on a
	// transient transaction error, and a re-run sees its own committed state.
	duplicate := false
	err := h.transactor.WithTransaction(ctx, func(ctx context.Context) error {
		alreadyProcessed, err := h.idempotency.IsProcessed(ctx, event.ID)
		if err != nil {
			return fmt.Errorf("check post comment like deleted event %s processed: %w", event.ID, err)
		}
		duplicate = alreadyProcessed
		if alreadyProcessed {
			return nil
		}

		input := usecase.DeleteCommentLikeInput{CommentID: event.CommentID, UserID: event.UserID, CorrelationID: event.CorrelationID}
		if err := h.usecase.Execute(ctx, input); err != nil {
			return fmt.Errorf("execute delete comment like usecase for event %s: %w", event.ID, err)
		}

		if err := h.idempotency.MarkProcessed(ctx, event.ID, event.CorrelationID, PostCommentLikeDeletedEventType); err != nil {
			return fmt.Errorf("mark post comment like deleted event %s processed: %w", event.ID, err)
		}

		return nil
	})
	if err != nil {
		return fmt.Errorf("project post comment like deleted event %s: %w", event.ID, err)
	}

	// Logged once the transaction committed, because the driver may re-run it.
	if duplicate {
		h.logger.WarnContext(ctx, "discarding duplicate post comment like deleted event", "event_id", event.ID, "correlation_id", event.CorrelationID, "userId", event.UserID)
		return nil
	}
	h.logger.InfoContext(ctx, "post comment like deleted event projected", "event_id", event.ID, "correlation_id", event.CorrelationID, "userId", event.UserID,
		"comment_id", event.CommentID)

	return nil
}

func validatePostCommentLikeDeletedEvent(event PostCommentLikeDeletedEvent) error {
	if event.ID == uuid.Nil {
		return fmt.Errorf("post comment like deleted event: id is required")
	}
	if event.CorrelationID == uuid.Nil {
		return fmt.Errorf("post comment like deleted event: correlationId is required")
	}
	if event.OccurredAt.IsZero() {
		return fmt.Errorf("post comment like deleted event: occurredAt is required")
	}
	if event.CommentID == uuid.Nil {
		return fmt.Errorf("post comment like deleted event: commentId is required")
	}
	if event.UserID == uuid.Nil {
		return fmt.Errorf("post comment like deleted event: userId is required")
	}

	return nil
}
