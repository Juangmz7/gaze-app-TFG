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
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/eventlog"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
)

// PostCommentCreatedEventType identifies this event for idempotency
// bookkeeping.
const PostCommentCreatedEventType = "PostCommentCreatedEvent"

// PostCommentCreatedEvent is the wire shape of the Java CommentCreatedEvent
// record published by post-command-service on rk.post.comment.created. Field
// names are camelCase. ReplyTo is null for a top-level comment.
type PostCommentCreatedEvent struct {
	ID            uuid.UUID `json:"id"`
	CorrelationID uuid.UUID `json:"correlationId"`
	OccurredAt    time.Time `json:"occurredAt"`
	CommentID     uuid.UUID `json:"commentId"`
	PostID        uuid.UUID `json:"postId"`
	UserID        uuid.UUID `json:"userId"`
	Content       string    `json:"content"`
	ReplyTo       uuid.UUID `json:"replyTo"`
	CreatedAt     time.Time `json:"createdAt"`
	UpdatedAt     time.Time `json:"updatedAt"`
}

// PostCommentCreatedIdempotencyRepository records and checks processed
// events.
type PostCommentCreatedIdempotencyRepository interface {
	IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error)
	MarkProcessed(ctx context.Context, eventID, correlationID uuid.UUID, eventType string) error
}

// PostCommentCreatedTransactor runs its function inside one MongoDB transaction.
// Implemented by shared/infrastructure/database.Transactor.
type PostCommentCreatedTransactor interface {
	WithTransaction(ctx context.Context, fn func(ctx context.Context) error) error
}

// PostCommentCreatedUsecase executes the comment creation projection.
type PostCommentCreatedUsecase interface {
	Execute(ctx context.Context, input usecase.RecordCommentInput) error
}

// PostCommentCreatedHandler decodes PostCommentCreatedEvent deliveries,
// enforces idempotency, and delegates to PostCommentCreatedUsecase.
type PostCommentCreatedHandler struct {
	transactor  PostCommentCreatedTransactor
	idempotency PostCommentCreatedIdempotencyRepository
	usecase     PostCommentCreatedUsecase
	logger      *slog.Logger
}

// NewPostCommentCreatedHandler creates a PostCommentCreatedHandler.
func NewPostCommentCreatedHandler(transactor PostCommentCreatedTransactor, idempotency PostCommentCreatedIdempotencyRepository, uc PostCommentCreatedUsecase, logger *slog.Logger) *PostCommentCreatedHandler {
	return &PostCommentCreatedHandler{transactor: transactor, idempotency: idempotency, usecase: uc, logger: logger}
}

// Handle implements dispatch.EventHandlerFunc.
func (h *PostCommentCreatedHandler) Handle(ctx context.Context, msg *message.Message) error {
	var event PostCommentCreatedEvent
	if err := json.Unmarshal(msg.Payload, &event); err != nil {
		eventlog.WarnInvalid(ctx, h.logger, PostCommentCreatedEventType, msg.Payload, err)
		return rmqerror.NewPermanent(fmt.Errorf("decode post comment created event: %w", err))
	}

	if err := validatePostCommentCreatedEvent(event); err != nil {
		eventlog.WarnInvalid(ctx, h.logger, PostCommentCreatedEventType, msg.Payload, err)
		return rmqerror.NewPermanent(err)
	}

	// IsProcessed, the projection (including the post stats adjustment) and
	// MarkProcessed commit or abort together. The driver may re-run this function
	// on a transient transaction error, and a re-run sees its own committed state.
	duplicate := false
	err := h.transactor.WithTransaction(ctx, func(ctx context.Context) error {
		alreadyProcessed, err := h.idempotency.IsProcessed(ctx, event.ID)
		if err != nil {
			return fmt.Errorf("check post comment created event %s processed: %w", event.ID, err)
		}
		duplicate = alreadyProcessed
		if alreadyProcessed {
			return nil
		}

		input := usecase.RecordCommentInput{
			CommentID: event.CommentID,
			PostID:    event.PostID,
			UserID:    event.UserID,
			Content:   event.Content,
			ReplyTo:   event.ReplyTo,
			CreatedAt: event.CreatedAt,
			UpdatedAt: event.UpdatedAt,
		}
		if err := h.usecase.Execute(ctx, input); err != nil {
			return fmt.Errorf("execute record comment usecase for event %s: %w", event.ID, err)
		}

		if err := h.idempotency.MarkProcessed(ctx, event.ID, event.CorrelationID, PostCommentCreatedEventType); err != nil {
			return fmt.Errorf("mark post comment created event %s processed: %w", event.ID, err)
		}

		return nil
	})
	if err != nil {
		return fmt.Errorf("project post comment created event %s: %w", event.ID, err)
	}

	// Logged once the transaction committed, because the driver may re-run it.
	if duplicate {
		h.logger.WarnContext(ctx, "discarding duplicate post comment created event", "event_id", event.ID, "correlation_id", event.CorrelationID, "userId", event.UserID)
		return nil
	}
	h.logger.InfoContext(ctx, "post comment created event projected", "event_id", event.ID, "correlation_id", event.CorrelationID, "userId", event.UserID, "comment_id", event.CommentID, "post_id", event.PostID)

	return nil
}

func validatePostCommentCreatedEvent(event PostCommentCreatedEvent) error {
	if event.ID == uuid.Nil {
		return fmt.Errorf("post comment created event: id is required")
	}
	if event.CorrelationID == uuid.Nil {
		return fmt.Errorf("post comment created event: correlationId is required")
	}
	if event.OccurredAt.IsZero() {
		return fmt.Errorf("post comment created event: occurredAt is required")
	}
	if event.CommentID == uuid.Nil {
		return fmt.Errorf("post comment created event: commentId is required")
	}
	if event.PostID == uuid.Nil {
		return fmt.Errorf("post comment created event: postId is required")
	}
	if event.UserID == uuid.Nil {
		return fmt.Errorf("post comment created event: userId is required")
	}

	return nil
}
