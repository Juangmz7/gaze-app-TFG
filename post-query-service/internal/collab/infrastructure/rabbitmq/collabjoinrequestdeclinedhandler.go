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

// CollabJoinRequestDeclinedEventType identifies this event for idempotency bookkeeping.
const CollabJoinRequestDeclinedEventType = "CollabJoinRequestDeclinedEvent"

// CollabJoinRequestDeclinedEvent is the wire shape of CollabJoinRequestDeclinedEvent
// published by post-command-service
// (collab/infrastructure/events/CollabJoinRequestDeclinedEvent.java) on
// rk.post.collab.request.declined. Field names are camelCase, matching the Java record;
// collabMemberStatus is always REJECTED. MemberCreatedAt is the member row's creation
// date (the request's), used only when this event creates the row; it falls back to
// occurredAt when absent.
type CollabJoinRequestDeclinedEvent struct {
	ID                 uuid.UUID  `json:"id"`
	CorrelationID      uuid.UUID  `json:"correlationId"`
	OccurredAt         time.Time  `json:"occurredAt"`
	CollabID           uuid.UUID  `json:"collabId"`
	UserID             uuid.UUID  `json:"userId"`
	DeclinedBy         uuid.UUID  `json:"declinedBy"`
	CollabMemberStatus string     `json:"collabMemberStatus"`
	Role               string     `json:"role"`
	MemberCreatedAt    *time.Time `json:"memberCreatedAt"`
}

// CollabJoinRequestDeclinedHandler decodes CollabJoinRequestDeclinedEvent deliveries
// and projects them into collab_members through the shared transactional, idempotent
// and forward-only projection.
type CollabJoinRequestDeclinedHandler struct {
	projector collabMemberProjector
}

// NewCollabJoinRequestDeclinedHandler creates a CollabJoinRequestDeclinedHandler.
func NewCollabJoinRequestDeclinedHandler(transactor CollabMemberTransactor, idempotency CollabMemberIdempotencyRepository, uc CollabMemberUsecase, logger *slog.Logger) *CollabJoinRequestDeclinedHandler {
	return &CollabJoinRequestDeclinedHandler{projector: collabMemberProjector{transactor: transactor, idempotency: idempotency, usecase: uc, logger: logger}}
}

// Handle implements dispatch.EventHandlerFunc.
func (h *CollabJoinRequestDeclinedHandler) Handle(ctx context.Context, msg *message.Message) error {
	var event CollabJoinRequestDeclinedEvent
	if err := json.Unmarshal(msg.Payload, &event); err != nil {
		eventlog.WarnInvalid(ctx, h.projector.logger, CollabJoinRequestDeclinedEventType, msg.Payload, err)
		return rmqerror.NewPermanent(fmt.Errorf("decode collab join request declined event: %w", err))
	}

	envelope := event.envelope()
	if err := validateCollabJoinRequestDeclinedEvent(event, envelope); err != nil {
		eventlog.WarnInvalid(ctx, h.projector.logger, CollabJoinRequestDeclinedEventType, msg.Payload, err)
		return rmqerror.NewPermanent(err)
	}

	return h.projector.project(ctx, "collab join request declined", CollabJoinRequestDeclinedEventType, envelope, collabMemberInput(envelope, event.MemberCreatedAt))
}

func (e CollabJoinRequestDeclinedEvent) envelope() collabMemberEnvelope {
	return collabMemberEnvelope{
		ID:            e.ID,
		CorrelationID: e.CorrelationID,
		OccurredAt:    e.OccurredAt,
		CollabID:      e.CollabID,
		UserID:        e.UserID,
		Status:        e.CollabMemberStatus,
		Role:          e.Role,
	}
}

func validateCollabJoinRequestDeclinedEvent(_ CollabJoinRequestDeclinedEvent, envelope collabMemberEnvelope) error {
	return validateCollabMemberEnvelope("collab join request declined", envelope, "collabMemberStatus", usecase.CollabMemberStatusRejected)
}
