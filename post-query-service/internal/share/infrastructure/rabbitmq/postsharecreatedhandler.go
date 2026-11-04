// Package rabbitmq handles PostShareCreatedEvent and PostShareDeletedEvent
// deliveries from topology.ExchangePostEvents (routing keys
// rk.post.share.created and rk.post.share.deleted).
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

// PostShareCreatedEventType identifies this event for idempotency
// bookkeeping.
const PostShareCreatedEventType = "PostShareCreatedEvent"

// PostShareCreatedEvent is the wire shape of the Java PostSharedEvent record
// published by post-command-service on rk.post.share.created. Field names are
// camelCase. It carries no shareId: post_shares in post-command-service has
// (post_id, user_id) as its primary key, so that pair is the share's identity.
type PostShareCreatedEvent struct {
	ID            uuid.UUID `json:"id"`
	CorrelationID uuid.UUID `json:"correlationId"`
	OccurredAt    time.Time `json:"occurredAt"`
	PostID        uuid.UUID `json:"postId"`
	UserID        uuid.UUID `json:"userId"`
	CreatedAt     time.Time `json:"createdAt"`
}

// PostShareCreatedIdempotencyRepository records and checks processed
// events.
type PostShareCreatedIdempotencyRepository interface {
	IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error)
	MarkProcessed(ctx context.Context, eventID, correlationID uuid.UUID, eventType string) error
}

// PostShareCreatedTransactor runs its function inside one MongoDB transaction.
// Implemented by shared/infrastructure/database.Transactor.
type PostShareCreatedTransactor interface {
	WithTransaction(ctx context.Context, fn func(ctx context.Context) error) error
}

// PostShareCreatedUsecase executes the share projection.
type PostShareCreatedUsecase interface {
	Execute(ctx context.Context, input usecase.RecordShareInput) error
}

// PostShareCreatedHandler decodes PostShareCreatedEvent deliveries,
// enforces idempotency, and delegates to PostShareCreatedUsecase.
type PostShareCreatedHandler struct {
	transactor  PostShareCreatedTransactor
	idempotency PostShareCreatedIdempotencyRepository
	usecase     PostShareCreatedUsecase
	logger      *slog.Logger
}

// NewPostShareCreatedHandler creates a PostShareCreatedHandler.
func NewPostShareCreatedHandler(transactor PostShareCreatedTransactor, idempotency PostShareCreatedIdempotencyRepository, usecase PostShareCreatedUsecase, logger *slog.Logger) *PostShareCreatedHandler {
	return &PostShareCreatedHandler{transactor: transactor, idempotency: idempotency, usecase: usecase, logger: logger}
}

// Handle implements dispatch.EventHandlerFunc.
func (h *PostShareCreatedHandler) Handle(ctx context.Context, msg *message.Message) error {
	var event PostShareCreatedEvent
	if err := json.Unmarshal(msg.Payload, &event); err != nil {
		return rmqerror.NewPermanent(fmt.Errorf("decode post share created event: %w", err))
	}

	if err := validatePostShareCreatedEvent(event); err != nil {
		return rmqerror.NewPermanent(err)
	}

	// IsProcessed, the projection (including the post stats adjustment) and
	// MarkProcessed commit or abort together. The driver may re-run this function
	// on a transient transaction error, and a re-run sees its own committed state.
	err := h.transactor.WithTransaction(ctx, func(ctx context.Context) error {
		alreadyProcessed, err := h.idempotency.IsProcessed(ctx, event.ID)
		if err != nil {
			return fmt.Errorf("check post share created event %s processed: %w", event.ID, err)
		}
		if alreadyProcessed {
			h.logger.WarnContext(ctx, "discarding duplicate post share created event", "event_id", event.ID)
			return nil
		}

		input := usecase.RecordShareInput{
			PostID:    event.PostID,
			UserID:    event.UserID,
			CreatedAt: event.CreatedAt,
		}
		if err := h.usecase.Execute(ctx, input); err != nil {
			return fmt.Errorf("execute record share usecase for event %s: %w", event.ID, err)
		}

		if err := h.idempotency.MarkProcessed(ctx, event.ID, event.CorrelationID, PostShareCreatedEventType); err != nil {
			return fmt.Errorf("mark post share created event %s processed: %w", event.ID, err)
		}

		return nil
	})
	if err != nil {
		return fmt.Errorf("project post share created event %s: %w", event.ID, err)
	}

	return nil
}

func validatePostShareCreatedEvent(event PostShareCreatedEvent) error {
	if event.ID == uuid.Nil {
		return fmt.Errorf("post share created event: id is required")
	}
	if event.CorrelationID == uuid.Nil {
		return fmt.Errorf("post share created event: correlationId is required")
	}
	if event.OccurredAt.IsZero() {
		return fmt.Errorf("post share created event: occurredAt is required")
	}
	if event.PostID == uuid.Nil {
		return fmt.Errorf("post share created event: postId is required")
	}
	if event.UserID == uuid.Nil {
		return fmt.Errorf("post share created event: userId is required")
	}

	return nil
}
