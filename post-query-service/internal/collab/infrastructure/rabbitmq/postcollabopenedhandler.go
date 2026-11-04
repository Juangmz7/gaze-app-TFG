// Package rabbitmq handles both variants of CollabOpenedEvent and
// CollabClosedEvent from topology.ExchangePostEvents (routing keys
// rk.post.collab.opened.post-created, rk.post.collab.opened.existing-post and
// rk.post.collab.closed).
package rabbitmq

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"time"

	"github.com/ThreeDotsLabs/watermill/message"
	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/application/usecase"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/eventlog"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
)

// PostCollabOpenedEventType identifies this event for idempotency
// bookkeeping.
const PostCollabOpenedEventType = "PostCollabOpenedPostCreatedEvent"

// PostCollabOpenedEvent is the wire shape of the Java CollabOpenedEvent record
// published by post-command-service on both rk.post.collab.opened.* keys. Field
// names are camelCase. Only the collab's own fields are modeled: the record's post
// fields (postType, description, postTags, media, ...) are ignored, because the
// post read model is projected from PostCreatedEvent, which post-command-service
// publishes for collab posts too once their media is verified.
type PostCollabOpenedEvent struct {
	ID              uuid.UUID `json:"id"`
	CorrelationID   uuid.UUID `json:"correlationId"`
	OccurredAt      time.Time `json:"occurredAt"`
	CollabID        uuid.UUID `json:"collabId"`
	Title           string    `json:"title"`
	CreatedBy       uuid.UUID `json:"createdBy"`
	CollabStatus    string    `json:"collabStatus"`
	CollabCreatedAt time.Time `json:"collabCreatedAt"`
	PostID          uuid.UUID `json:"postId"`
}

// PostCollabOpenedIdempotencyRepository records and checks processed
// events.
type PostCollabOpenedIdempotencyRepository interface {
	IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error)
	MarkProcessed(ctx context.Context, eventID, correlationID uuid.UUID, eventType string) error
}

// PostCollabOpenedUsecase executes the collab creation projection.
type PostCollabOpenedUsecase interface {
	Execute(ctx context.Context, input usecase.RecordCollabOpenedInput) error
}

// PostCollabOpenedHandler decodes the event, enforces idempotency, and
// delegates to PostCollabOpenedUsecase.
type PostCollabOpenedHandler struct {
	idempotency PostCollabOpenedIdempotencyRepository
	usecase     PostCollabOpenedUsecase
	logger      *slog.Logger
}

// NewPostCollabOpenedHandler creates a PostCollabOpenedHandler.
func NewPostCollabOpenedHandler(idempotency PostCollabOpenedIdempotencyRepository, usecase PostCollabOpenedUsecase, logger *slog.Logger) *PostCollabOpenedHandler {
	return &PostCollabOpenedHandler{idempotency: idempotency, usecase: usecase, logger: logger}
}

// Handle implements dispatch.EventHandlerFunc.
func (h *PostCollabOpenedHandler) Handle(ctx context.Context, msg *message.Message) error {
	var event PostCollabOpenedEvent
	if err := json.Unmarshal(msg.Payload, &event); err != nil {
		eventlog.WarnInvalid(ctx, h.logger, PostCollabOpenedEventType, msg.Payload, err)
		return rmqerror.NewPermanent(fmt.Errorf("decode post collab opened event: %w", err))
	}

	if err := validatePostCollabOpenedEvent(event); err != nil {
		eventlog.WarnInvalid(ctx, h.logger, PostCollabOpenedEventType, msg.Payload, err)
		return rmqerror.NewPermanent(err)
	}

	alreadyProcessed, err := h.idempotency.IsProcessed(ctx, event.ID)
	if err != nil {
		return fmt.Errorf("check post collab opened event %s processed: %w", event.ID, err)
	}
	if alreadyProcessed {
		h.logger.WarnContext(ctx, "discarding duplicate post collab opened event", "event_id", event.ID, "correlation_id", event.CorrelationID, "userId", event.CreatedBy)
		return nil
	}

	input := usecase.RecordCollabOpenedInput{
		CollabID:    event.CollabID,
		PostID:      event.PostID,
		OwnerUserID: event.CreatedBy,
		Title:       event.Title,
		Status:      event.CollabStatus,
		CreatedAt:   event.CollabCreatedAt,
	}
	if err := h.usecase.Execute(ctx, input); err != nil {
		return fmt.Errorf("execute record collab opened usecase for event %s: %w", event.ID, err)
	}

	if err := h.idempotency.MarkProcessed(ctx, event.ID, event.CorrelationID, PostCollabOpenedEventType); err != nil {
		return fmt.Errorf("mark post collab opened event %s processed: %w", event.ID, err)
	}

	h.logger.InfoContext(ctx, "post collab opened event projected", "event_id", event.ID, "correlation_id", event.CorrelationID, "userId", event.CreatedBy, "collab_id", event.CollabID, "post_id", event.PostID)

	return nil
}

func validatePostCollabOpenedEvent(event PostCollabOpenedEvent) error {
	if event.ID == uuid.Nil {
		return fmt.Errorf("post collab opened event: id is required")
	}
	if event.CorrelationID == uuid.Nil {
		return fmt.Errorf("post collab opened event: correlationId is required")
	}
	if event.OccurredAt.IsZero() {
		return fmt.Errorf("post collab opened event: occurredAt is required")
	}
	if event.CollabID == uuid.Nil {
		return fmt.Errorf("post collab opened event: collabId is required")
	}
	if event.PostID == uuid.Nil {
		return fmt.Errorf("post collab opened event: postId is required")
	}
	if event.CreatedBy == uuid.Nil {
		return fmt.Errorf("post collab opened event: createdBy is required")
	}

	return nil
}
