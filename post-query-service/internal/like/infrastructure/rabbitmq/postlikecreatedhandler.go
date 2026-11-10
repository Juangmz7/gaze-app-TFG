// Package rabbitmq handles PostLikeCreatedEvent and PostLikeDeletedEvent
// deliveries from topology.ExchangePostEvents (routing keys
// rk.post.like.created and rk.post.like.deleted).
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
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/eventlog"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
)

// PostLikeCreatedEventType identifies this event for idempotency
// bookkeeping.
const PostLikeCreatedEventType = "PostLikeCreatedEvent"

// PostLikeCreatedEvent is the wire shape of PostLikeCreatedEvent published
// by post-command-service. Field names are camelCase, matching the real Java
// record. It carries no likeId: post_likes in post-command-service has
// (post_id, user_id) as its primary key, so that pair is the like's identity.
type PostLikeCreatedEvent struct {
	ID            uuid.UUID `json:"id"`
	CorrelationID uuid.UUID `json:"correlationId"`
	OccurredAt    time.Time `json:"occurredAt"`
	PostID        uuid.UUID `json:"postId"`
	UserID        uuid.UUID `json:"userId"`
	Source        string    `json:"source"`
	FeedPosition  int       `json:"feedPosition"`
	CreatedAt     time.Time `json:"createdAt"`
}

// PostLikeCreatedIdempotencyRepository records and checks processed events.
type PostLikeCreatedIdempotencyRepository interface {
	IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error)
	MarkProcessed(ctx context.Context, eventID, correlationID uuid.UUID, eventType string) error
}

// PostLikeCreatedTransactor runs its function inside one MongoDB transaction.
// Implemented by shared/infrastructure/database.Transactor.
type PostLikeCreatedTransactor interface {
	WithTransaction(ctx context.Context, fn func(ctx context.Context) error) error
}

// PostLikeCreatedUsecase executes the like projection.
type PostLikeCreatedUsecase interface {
	Execute(ctx context.Context, input usecase.RecordLikeInput) error
}

// PostLikeCreatedHandler decodes PostLikeCreatedEvent deliveries, enforces
// idempotency, and delegates to PostLikeCreatedUsecase.
type PostLikeCreatedHandler struct {
	transactor  PostLikeCreatedTransactor
	idempotency PostLikeCreatedIdempotencyRepository
	usecase     PostLikeCreatedUsecase
	logger      *slog.Logger
}

// NewPostLikeCreatedHandler creates a PostLikeCreatedHandler.
func NewPostLikeCreatedHandler(transactor PostLikeCreatedTransactor, idempotency PostLikeCreatedIdempotencyRepository, usecase PostLikeCreatedUsecase, logger *slog.Logger) *PostLikeCreatedHandler {
	return &PostLikeCreatedHandler{transactor: transactor, idempotency: idempotency, usecase: usecase, logger: logger}
}

// Handle implements dispatch.EventHandlerFunc.
func (h *PostLikeCreatedHandler) Handle(ctx context.Context, msg *message.Message) error {
	var event PostLikeCreatedEvent
	if err := json.Unmarshal(msg.Payload, &event); err != nil {
		eventlog.WarnInvalid(ctx, h.logger, PostLikeCreatedEventType, msg.Payload, err)
		return rmqerror.NewPermanent(fmt.Errorf("decode post like created event: %w", err))
	}

	if err := validatePostLikeCreatedEvent(event); err != nil {
		eventlog.WarnInvalid(ctx, h.logger, PostLikeCreatedEventType, msg.Payload, err)
		return rmqerror.NewPermanent(err)
	}

	// IsProcessed, the projection (including the post stats adjustment) and
	// MarkProcessed commit or abort together. The driver may re-run this function
	// on a transient transaction error, and a re-run sees its own committed state.
	duplicate := false
	err := h.transactor.WithTransaction(ctx, func(ctx context.Context) error {
		alreadyProcessed, err := h.idempotency.IsProcessed(ctx, event.ID)
		if err != nil {
			return fmt.Errorf("check post like created event %s processed: %w", event.ID, err)
		}
		duplicate = alreadyProcessed
		if alreadyProcessed {
			return nil
		}

		input := usecase.RecordLikeInput{
			PostID:    event.PostID,
			UserID:    event.UserID,
			CreatedAt: event.CreatedAt,
		}
		if err := h.usecase.Execute(ctx, input); err != nil {
			return fmt.Errorf("execute record like usecase for event %s: %w", event.ID, err)
		}

		if err := h.idempotency.MarkProcessed(ctx, event.ID, event.CorrelationID, PostLikeCreatedEventType); err != nil {
			return fmt.Errorf("mark post like created event %s processed: %w", event.ID, err)
		}

		return nil
	})
	if err != nil {
		return fmt.Errorf("project post like created event %s: %w", event.ID, err)
	}

	// Logged once the transaction committed, because the driver may re-run it.
	if duplicate {
		h.logger.WarnContext(ctx, "discarding duplicate post like created event", "event_id", event.ID, "correlation_id", event.CorrelationID, "userId", event.UserID)
		return nil
	}
	h.logger.InfoContext(ctx, "post like created event projected", "event_id", event.ID, "correlation_id", event.CorrelationID, "userId", event.UserID, "post_id", event.PostID)

	return nil
}

func validatePostLikeCreatedEvent(event PostLikeCreatedEvent) error {
	if event.ID == uuid.Nil {
		return fmt.Errorf("post like created event: id is required")
	}
	if event.CorrelationID == uuid.Nil {
		return fmt.Errorf("post like created event: correlationId is required")
	}
	if event.OccurredAt.IsZero() {
		return fmt.Errorf("post like created event: occurredAt is required")
	}
	if event.PostID == uuid.Nil {
		return fmt.Errorf("post like created event: postId is required")
	}
	if event.UserID == uuid.Nil {
		return fmt.Errorf("post like created event: userId is required")
	}

	return nil
}
