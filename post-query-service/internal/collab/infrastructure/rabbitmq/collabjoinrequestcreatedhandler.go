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

// CollabJoinRequestCreatedEventType identifies this event for idempotency bookkeeping.
const CollabJoinRequestCreatedEventType = "CollabJoinRequestCreatedEvent"

// CollabJoinRequestCreatedEvent is the wire shape of CollabJoinRequestCreatedEvent
// published by post-command-service
// (collab/infrastructure/events/CollabJoinRequestCreatedEvent.java) on
// rk.post.collab.request.created. Field names are camelCase, matching the Java record;
// status is always PENDING. CreatedAt (the request's date) is required: it is the
// requests list's sort key.
type CollabJoinRequestCreatedEvent struct {
	ID            uuid.UUID  `json:"id"`
	CorrelationID uuid.UUID  `json:"correlationId"`
	OccurredAt    time.Time  `json:"occurredAt"`
	CollabID      uuid.UUID  `json:"collabId"`
	UserID        uuid.UUID  `json:"userId"`
	Status        string     `json:"status"`
	Role          string     `json:"role"`
	CreatedAt     *time.Time `json:"createdAt"`
}

// CollabJoinRequestCreatedHandler decodes CollabJoinRequestCreatedEvent deliveries and
// projects them into collab_members through the shared transactional, idempotent and
// forward-only projection.
type CollabJoinRequestCreatedHandler struct {
	projector collabMemberProjector
}

// NewCollabJoinRequestCreatedHandler creates a CollabJoinRequestCreatedHandler.
func NewCollabJoinRequestCreatedHandler(transactor CollabMemberTransactor, idempotency CollabMemberIdempotencyRepository, uc CollabMemberUsecase, logger *slog.Logger) *CollabJoinRequestCreatedHandler {
	return &CollabJoinRequestCreatedHandler{projector: collabMemberProjector{transactor: transactor, idempotency: idempotency, usecase: uc, logger: logger}}
}

// Handle implements dispatch.EventHandlerFunc.
func (h *CollabJoinRequestCreatedHandler) Handle(ctx context.Context, msg *message.Message) error {
	var event CollabJoinRequestCreatedEvent
	if err := json.Unmarshal(msg.Payload, &event); err != nil {
		eventlog.WarnInvalid(ctx, h.projector.logger, CollabJoinRequestCreatedEventType, msg.Payload, err)
		return rmqerror.NewPermanent(fmt.Errorf("decode collab join request created event: %w", err))
	}

	envelope := event.envelope()
	if err := validateCollabJoinRequestCreatedEvent(event, envelope); err != nil {
		eventlog.WarnInvalid(ctx, h.projector.logger, CollabJoinRequestCreatedEventType, msg.Payload, err)
		return rmqerror.NewPermanent(err)
	}

	return h.projector.project(ctx, "collab join request created", CollabJoinRequestCreatedEventType, envelope, collabMemberInput(envelope, event.CreatedAt))
}

func (e CollabJoinRequestCreatedEvent) envelope() collabMemberEnvelope {
	return collabMemberEnvelope{
		ID:            e.ID,
		CorrelationID: e.CorrelationID,
		OccurredAt:    e.OccurredAt,
		CollabID:      e.CollabID,
		UserID:        e.UserID,
		Status:        e.Status,
		Role:          e.Role,
	}
}

func validateCollabJoinRequestCreatedEvent(event CollabJoinRequestCreatedEvent, envelope collabMemberEnvelope) error {
	if err := validateCollabMemberEnvelope("collab join request created", envelope, "status", usecase.CollabMemberStatusPending); err != nil {
		return err
	}
	if event.CreatedAt == nil || event.CreatedAt.IsZero() {
		return fmt.Errorf("collab join request created event: createdAt is required")
	}

	return nil
}
