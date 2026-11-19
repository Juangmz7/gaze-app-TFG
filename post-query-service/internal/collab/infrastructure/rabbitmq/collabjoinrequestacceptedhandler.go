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

// CollabJoinRequestAcceptedEventType identifies this event for idempotency bookkeeping.
const CollabJoinRequestAcceptedEventType = "CollabJoinRequestAcceptedEvent"

// CollabJoinRequestAcceptedEvent is the wire shape of CollabJoinRequestAcceptedEvent
// published by post-command-service
// (collab/infrastructure/events/CollabJoinRequestAcceptedEvent.java) on
// rk.post.collab.request.accepted. Field names are camelCase, matching the Java record;
// collabMemberStatus is always ACCEPTED. MemberCreatedAt is the member row's creation
// date (the request's), used only when this event creates the row; it falls back to
// occurredAt when absent.
type CollabJoinRequestAcceptedEvent struct {
	ID                 uuid.UUID  `json:"id"`
	CorrelationID      uuid.UUID  `json:"correlationId"`
	OccurredAt         time.Time  `json:"occurredAt"`
	CollabID           uuid.UUID  `json:"collabId"`
	UserID             uuid.UUID  `json:"userId"`
	AcceptedBy         uuid.UUID  `json:"acceptedBy"`
	CollabMemberStatus string     `json:"collabMemberStatus"`
	Role               string     `json:"role"`
	MemberCreatedAt    *time.Time `json:"memberCreatedAt"`
}

// CollabJoinRequestAcceptedHandler decodes CollabJoinRequestAcceptedEvent deliveries
// and projects them into collab_members through the shared transactional, idempotent
// and forward-only projection.
type CollabJoinRequestAcceptedHandler struct {
	projector collabMemberProjector
}

// NewCollabJoinRequestAcceptedHandler creates a CollabJoinRequestAcceptedHandler.
func NewCollabJoinRequestAcceptedHandler(transactor CollabMemberTransactor, idempotency CollabMemberIdempotencyRepository, uc CollabMemberUsecase, logger *slog.Logger) *CollabJoinRequestAcceptedHandler {
	return &CollabJoinRequestAcceptedHandler{projector: collabMemberProjector{transactor: transactor, idempotency: idempotency, usecase: uc, logger: logger}}
}

// Handle implements dispatch.EventHandlerFunc.
func (h *CollabJoinRequestAcceptedHandler) Handle(ctx context.Context, msg *message.Message) error {
	var event CollabJoinRequestAcceptedEvent
	if err := json.Unmarshal(msg.Payload, &event); err != nil {
		eventlog.WarnInvalid(ctx, h.projector.logger, CollabJoinRequestAcceptedEventType, msg.Payload, err)
		return rmqerror.NewPermanent(fmt.Errorf("decode collab join request accepted event: %w", err))
	}

	envelope := event.envelope()
	if err := validateCollabJoinRequestAcceptedEvent(event, envelope); err != nil {
		eventlog.WarnInvalid(ctx, h.projector.logger, CollabJoinRequestAcceptedEventType, msg.Payload, err)
		return rmqerror.NewPermanent(err)
	}

	return h.projector.project(ctx, "collab join request accepted", CollabJoinRequestAcceptedEventType, envelope, collabMemberInput(envelope, event.MemberCreatedAt))
}

func (e CollabJoinRequestAcceptedEvent) envelope() collabMemberEnvelope {
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

func validateCollabJoinRequestAcceptedEvent(_ CollabJoinRequestAcceptedEvent, envelope collabMemberEnvelope) error {
	return validateCollabMemberEnvelope("collab join request accepted", envelope, "collabMemberStatus", usecase.CollabMemberStatusAccepted)
}
