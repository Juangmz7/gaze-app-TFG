// Package rabbitmq handles PostViewedEvent deliveries from
// topology.ExchangePostEvents (routing key rk.post.viewed).
package rabbitmq

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"time"

	"github.com/ThreeDotsLabs/watermill/message"
	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/eventlog"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/view/application/usecase"
)

// PostViewedEventType identifies this event for idempotency bookkeeping.
const PostViewedEventType = "PostViewedEvent"

// PostViewedEvent is the wire shape of PostViewedEvent published by
// post-command-service (view/infrastructure/events/PostViewedEvent.java). Field names
// are camelCase, matching the Java record. Only the envelope, the ids and
// serverTimestamp feed the history and the view counter; the rest is kept so the
// shape stays the contract's.
type PostViewedEvent struct {
	ID                uuid.UUID `json:"id"`
	CorrelationID     uuid.UUID `json:"correlationId"`
	OccurredAt        time.Time `json:"occurredAt"`
	ViewID            uuid.UUID `json:"viewId"`
	PostID            uuid.UUID `json:"postId"`
	UserID            uuid.UUID `json:"userId"`
	Source            string    `json:"source"`
	FeedPosition      int       `json:"feedPosition"`
	DurationMs        int       `json:"durationMs"`
	TimeWatchedMs     int       `json:"timeWatchedMs"`
	CompletionPercent int       `json:"completionPercent"`
	ExitReason        string    `json:"exitReason"`
	ServerTimestamp   time.Time `json:"serverTimestamp"`
	ReplayCount       int       `json:"replayCount"`
}

// PostViewedIdempotencyRepository records and checks processed events.
type PostViewedIdempotencyRepository interface {
	IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error)
	MarkProcessed(ctx context.Context, eventID, correlationID uuid.UUID, eventType string) error
}

// PostViewedTransactor runs its function inside one MongoDB transaction. Implemented
// by shared/infrastructure/database.Transactor.
type PostViewedTransactor interface {
	WithTransaction(ctx context.Context, fn func(ctx context.Context) error) error
}

// PostViewedUsecase executes the viewing history and view counter projection.
type PostViewedUsecase interface {
	Execute(ctx context.Context, input usecase.RecordPostViewInput) error
}

// PostViewedHandler decodes PostViewedEvent deliveries, enforces idempotency, and
// delegates to PostViewedUsecase.
type PostViewedHandler struct {
	transactor  PostViewedTransactor
	idempotency PostViewedIdempotencyRepository
	usecase     PostViewedUsecase
	logger      *slog.Logger
}

// NewPostViewedHandler creates a PostViewedHandler.
func NewPostViewedHandler(transactor PostViewedTransactor, idempotency PostViewedIdempotencyRepository, usecase PostViewedUsecase, logger *slog.Logger) *PostViewedHandler {
	return &PostViewedHandler{transactor: transactor, idempotency: idempotency, usecase: usecase, logger: logger}
}

// Handle implements dispatch.EventHandlerFunc.
func (h *PostViewedHandler) Handle(ctx context.Context, msg *message.Message) error {
	var event PostViewedEvent
	if err := json.Unmarshal(msg.Payload, &event); err != nil {
		eventlog.WarnInvalid(ctx, h.logger, PostViewedEventType, msg.Payload, err)
		return rmqerror.NewPermanent(fmt.Errorf("decode post viewed event: %w", err))
	}

	if err := validatePostViewedEvent(event); err != nil {
		eventlog.WarnInvalid(ctx, h.logger, PostViewedEventType, msg.Payload, err)
		return rmqerror.NewPermanent(err)
	}

	// IsProcessed, the history write, the view count and MarkProcessed commit or abort
	// together, so a redelivery never counts a view twice. The driver may re-run this
	// function on a transient transaction error, and a re-run sees its own committed
	// state.
	duplicate := false
	err := h.transactor.WithTransaction(ctx, func(ctx context.Context) error {
		alreadyProcessed, err := h.idempotency.IsProcessed(ctx, event.ID)
		if err != nil {
			return fmt.Errorf("check post viewed event %s processed: %w", event.ID, err)
		}
		duplicate = alreadyProcessed
		if alreadyProcessed {
			return nil
		}

		input := usecase.RecordPostViewInput{
			UserID:        event.UserID,
			PostID:        event.PostID,
			ViewedAt:      event.ServerTimestamp,
			CorrelationID: event.CorrelationID,
		}
		if err := h.usecase.Execute(ctx, input); err != nil {
			return fmt.Errorf("execute record post view usecase for event %s: %w", event.ID, err)
		}

		if err := h.idempotency.MarkProcessed(ctx, event.ID, event.CorrelationID, PostViewedEventType); err != nil {
			return fmt.Errorf("mark post viewed event %s processed: %w", event.ID, err)
		}

		return nil
	})
	if err != nil {
		return fmt.Errorf("project post viewed event %s: %w", event.ID, err)
	}

	// Logged once the transaction committed, because the driver may re-run it.
	if duplicate {
		h.logger.WarnContext(ctx, "discarding duplicate post viewed event", "event_id", event.ID, "correlation_id", event.CorrelationID, "userId", event.UserID)
		return nil
	}
	h.logger.InfoContext(ctx, "post viewed event projected", "event_id", event.ID, "correlation_id", event.CorrelationID, "userId", event.UserID, "post_id", event.PostID)

	return nil
}

func validatePostViewedEvent(event PostViewedEvent) error {
	if event.ID == uuid.Nil {
		return fmt.Errorf("post viewed event: id is required")
	}
	if event.CorrelationID == uuid.Nil {
		return fmt.Errorf("post viewed event: correlationId is required")
	}
	if event.OccurredAt.IsZero() {
		return fmt.Errorf("post viewed event: occurredAt is required")
	}
	if event.PostID == uuid.Nil {
		return fmt.Errorf("post viewed event: postId is required")
	}
	if event.UserID == uuid.Nil {
		return fmt.Errorf("post viewed event: userId is required")
	}
	if event.ServerTimestamp.IsZero() {
		return fmt.Errorf("post viewed event: serverTimestamp is required")
	}

	return nil
}
