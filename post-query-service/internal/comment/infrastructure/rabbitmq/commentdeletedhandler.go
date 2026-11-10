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

// CommentDeletedEventType identifies this event for idempotency
// bookkeeping.
const CommentDeletedEventType = "CommentDeletedEvent"

// CommentDeletedEvent is the wire shape of CommentDeletedEvent published by
// post-command-service. Field names are camelCase, matching the real Java
// CommentDeletedEvent record. Unlike CommentUpdatedEvent, CommentDeletedEvent
// implements the standard EventMessage envelope.
type CommentDeletedEvent struct {
	ID            uuid.UUID `json:"id"`
	CorrelationID uuid.UUID `json:"correlationId"`
	CommentID     uuid.UUID `json:"commentId"`
	PostID        uuid.UUID `json:"postId"`
	UserID        uuid.UUID `json:"userId"`
	OccurredAt    time.Time `json:"occurredAt"`
}

// CommentDeletedIdempotencyRepository records and checks processed events.
type CommentDeletedIdempotencyRepository interface {
	IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error)
	MarkProcessed(ctx context.Context, eventID, correlationID uuid.UUID, eventType string) error
}

// CommentDeletedTransactor runs its function inside one MongoDB transaction.
// Implemented by shared/infrastructure/database.Transactor.
type CommentDeletedTransactor interface {
	WithTransaction(ctx context.Context, fn func(ctx context.Context) error) error
}

// CommentDeletedUsecase executes the comment deletion projection.
type CommentDeletedUsecase interface {
	Execute(ctx context.Context, input usecase.DeleteCommentInput) error
}

// CommentDeletedHandler decodes CommentDeletedEvent deliveries, enforces
// idempotency, and delegates to CommentDeletedUsecase.
type CommentDeletedHandler struct {
	transactor  CommentDeletedTransactor
	idempotency CommentDeletedIdempotencyRepository
	usecase     CommentDeletedUsecase
	logger      *slog.Logger
}

// NewCommentDeletedHandler creates a CommentDeletedHandler.
func NewCommentDeletedHandler(transactor CommentDeletedTransactor, idempotency CommentDeletedIdempotencyRepository, uc CommentDeletedUsecase, logger *slog.Logger) *CommentDeletedHandler {
	return &CommentDeletedHandler{transactor: transactor, idempotency: idempotency, usecase: uc, logger: logger}
}

// Handle implements dispatch.EventHandlerFunc.
func (h *CommentDeletedHandler) Handle(ctx context.Context, msg *message.Message) error {
	var event CommentDeletedEvent
	if err := json.Unmarshal(msg.Payload, &event); err != nil {
		eventlog.WarnInvalid(ctx, h.logger, CommentDeletedEventType, msg.Payload, err)
		return rmqerror.NewPermanent(fmt.Errorf("decode comment deleted event: %w", err))
	}

	if err := validateCommentDeletedEvent(event); err != nil {
		eventlog.WarnInvalid(ctx, h.logger, CommentDeletedEventType, msg.Payload, err)
		return rmqerror.NewPermanent(err)
	}

	// IsProcessed, the projection (including the post stats adjustment) and
	// MarkProcessed commit or abort together. The driver may re-run this function
	// on a transient transaction error, and a re-run sees its own committed state.
	duplicate := false
	err := h.transactor.WithTransaction(ctx, func(ctx context.Context) error {
		alreadyProcessed, err := h.idempotency.IsProcessed(ctx, event.ID)
		if err != nil {
			return fmt.Errorf("check comment deleted event %s processed: %w", event.ID, err)
		}
		duplicate = alreadyProcessed
		if alreadyProcessed {
			return nil
		}

		input := usecase.DeleteCommentInput{CommentID: event.CommentID, PostID: event.PostID, UserID: event.UserID, CorrelationID: event.CorrelationID}
		if err := h.usecase.Execute(ctx, input); err != nil {
			return fmt.Errorf("execute delete comment usecase for event %s: %w", event.ID, err)
		}

		if err := h.idempotency.MarkProcessed(ctx, event.ID, event.CorrelationID, CommentDeletedEventType); err != nil {
			return fmt.Errorf("mark comment deleted event %s processed: %w", event.ID, err)
		}

		return nil
	})
	if err != nil {
		return fmt.Errorf("project comment deleted event %s: %w", event.ID, err)
	}

	// Logged once the transaction committed, because the driver may re-run it.
	if duplicate {
		h.logger.WarnContext(ctx, "discarding duplicate comment deleted event", "event_id", event.ID, "correlation_id", event.CorrelationID, "userId", event.UserID)
		return nil
	}
	h.logger.InfoContext(ctx, "comment deleted event projected", "event_id", event.ID, "correlation_id", event.CorrelationID, "userId", event.UserID, "comment_id", event.CommentID, "post_id", event.PostID)

	return nil
}

func validateCommentDeletedEvent(event CommentDeletedEvent) error {
	if event.ID == uuid.Nil {
		return fmt.Errorf("comment deleted event: id is required")
	}
	if event.CorrelationID == uuid.Nil {
		return fmt.Errorf("comment deleted event: correlationId is required")
	}
	if event.OccurredAt.IsZero() {
		return fmt.Errorf("comment deleted event: occurredAt is required")
	}
	if event.CommentID == uuid.Nil {
		return fmt.Errorf("comment deleted event: commentId is required")
	}
	if event.PostID == uuid.Nil {
		return fmt.Errorf("comment deleted event: postId is required")
	}
	if event.UserID == uuid.Nil {
		return fmt.Errorf("comment deleted event: userId is required")
	}

	return nil
}
