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

// PostCommentLikeCreatedEventType identifies this event for idempotency bookkeeping.
const PostCommentLikeCreatedEventType = "PostCommentLikeCreatedEvent"

// PostCommentLikeCreatedEvent is the wire shape of PostCommentLikeCreatedEvent
// published by post-command-service on rk.post.comment.like.created. Field names are
// camelCase, matching the real Java record. It carries no like id: comment_likes in
// post-command-service has (comment_id, user_id) as its primary key.
type PostCommentLikeCreatedEvent struct {
	ID            uuid.UUID `json:"id"`
	CorrelationID uuid.UUID `json:"correlationId"`
	OccurredAt    time.Time `json:"occurredAt"`
	PostID        uuid.UUID `json:"postId"`
	CommentID     uuid.UUID `json:"commentId"`
	UserID        uuid.UUID `json:"userId"`
	Source        string    `json:"source"`
	FeedPosition  int       `json:"feedPosition"`
	CreatedAt     time.Time `json:"createdAt"`
}

// PostCommentLikeCreatedIdempotencyRepository records and checks processed events.
type PostCommentLikeCreatedIdempotencyRepository interface {
	IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error)
	MarkProcessed(ctx context.Context, eventID, correlationID uuid.UUID, eventType string) error
}

// PostCommentLikeCreatedTransactor runs its function inside one MongoDB transaction.
// Implemented by shared/infrastructure/database.Transactor.
type PostCommentLikeCreatedTransactor interface {
	WithTransaction(ctx context.Context, fn func(ctx context.Context) error) error
}

// PostCommentLikeCreatedUsecase executes the comment like projection.
type PostCommentLikeCreatedUsecase interface {
	Execute(ctx context.Context, input usecase.RecordCommentLikeInput) error
}

// PostCommentLikeCreatedHandler decodes PostCommentLikeCreatedEvent deliveries,
// enforces idempotency, and delegates to PostCommentLikeCreatedUsecase.
type PostCommentLikeCreatedHandler struct {
	transactor  PostCommentLikeCreatedTransactor
	idempotency PostCommentLikeCreatedIdempotencyRepository
	usecase     PostCommentLikeCreatedUsecase
	logger      *slog.Logger
}

// NewPostCommentLikeCreatedHandler creates a PostCommentLikeCreatedHandler.
func NewPostCommentLikeCreatedHandler(transactor PostCommentLikeCreatedTransactor, idempotency PostCommentLikeCreatedIdempotencyRepository, uc PostCommentLikeCreatedUsecase, logger *slog.Logger) *PostCommentLikeCreatedHandler {
	return &PostCommentLikeCreatedHandler{transactor: transactor, idempotency: idempotency, usecase: uc, logger: logger}
}

// Handle implements dispatch.EventHandlerFunc.
func (h *PostCommentLikeCreatedHandler) Handle(ctx context.Context, msg *message.Message) error {
	var event PostCommentLikeCreatedEvent
	if err := json.Unmarshal(msg.Payload, &event); err != nil {
		eventlog.WarnInvalid(ctx, h.logger, PostCommentLikeCreatedEventType, msg.Payload, err)
		return rmqerror.NewPermanent(fmt.Errorf("decode post comment like created event: %w", err))
	}

	if err := validatePostCommentLikeCreatedEvent(event); err != nil {
		eventlog.WarnInvalid(ctx, h.logger, PostCommentLikeCreatedEventType, msg.Payload, err)
		return rmqerror.NewPermanent(err)
	}

	// IsProcessed, the projection (including the comment's like counter) and
	// MarkProcessed commit or abort together. The driver may re-run this function on a
	// transient transaction error, and a re-run sees its own committed state.
	duplicate := false
	err := h.transactor.WithTransaction(ctx, func(ctx context.Context) error {
		alreadyProcessed, err := h.idempotency.IsProcessed(ctx, event.ID)
		if err != nil {
			return fmt.Errorf("check post comment like created event %s processed: %w", event.ID, err)
		}
		duplicate = alreadyProcessed
		if alreadyProcessed {
			return nil
		}

		input := usecase.RecordCommentLikeInput{
			CommentID: event.CommentID,
			PostID:    event.PostID,
			UserID:    event.UserID,
			CreatedAt: event.CreatedAt,
		}
		if err := h.usecase.Execute(ctx, input); err != nil {
			return fmt.Errorf("execute record comment like usecase for event %s: %w", event.ID, err)
		}

		if err := h.idempotency.MarkProcessed(ctx, event.ID, event.CorrelationID, PostCommentLikeCreatedEventType); err != nil {
			return fmt.Errorf("mark post comment like created event %s processed: %w", event.ID, err)
		}

		return nil
	})
	if err != nil {
		return fmt.Errorf("project post comment like created event %s: %w", event.ID, err)
	}

	// Logged once the transaction committed, because the driver may re-run it.
	if duplicate {
		h.logger.WarnContext(ctx, "discarding duplicate post comment like created event", "event_id", event.ID, "correlation_id", event.CorrelationID, "userId", event.UserID)
		return nil
	}
	h.logger.InfoContext(ctx, "post comment like created event projected", "event_id", event.ID, "correlation_id", event.CorrelationID, "userId", event.UserID,
		"comment_id", event.CommentID, "post_id", event.PostID)

	return nil
}

func validatePostCommentLikeCreatedEvent(event PostCommentLikeCreatedEvent) error {
	if event.ID == uuid.Nil {
		return fmt.Errorf("post comment like created event: id is required")
	}
	if event.CorrelationID == uuid.Nil {
		return fmt.Errorf("post comment like created event: correlationId is required")
	}
	if event.OccurredAt.IsZero() {
		return fmt.Errorf("post comment like created event: occurredAt is required")
	}
	if event.CommentID == uuid.Nil {
		return fmt.Errorf("post comment like created event: commentId is required")
	}
	if event.UserID == uuid.Nil {
		return fmt.Errorf("post comment like created event: userId is required")
	}

	return nil
}
