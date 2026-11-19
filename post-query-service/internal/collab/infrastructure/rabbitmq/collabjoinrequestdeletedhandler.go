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

// CollabJoinRequestDeletedEventType identifies this event for idempotency bookkeeping.
const CollabJoinRequestDeletedEventType = "CollabJoinRequestDeletedEvent"

// CollabJoinRequestDeletedEvent is the wire shape of CollabJoinRequestDeletedEvent
// published by post-command-service
// (collab/infrastructure/events/CollabJoinRequestDeletedEvent.java) on
// rk.post.collab.request.deleted. Field names are camelCase, matching the Java record;
// collabMemberStatus is always DELETED. MemberCreatedAt is the member row's creation
// date (the request's), used only when this event creates the row; it falls back to
// occurredAt when absent.
type CollabJoinRequestDeletedEvent struct {
	ID                 uuid.UUID  `json:"id"`
	CorrelationID      uuid.UUID  `json:"correlationId"`
	OccurredAt         time.Time  `json:"occurredAt"`
	CollabID           uuid.UUID  `json:"collabId"`
	UserID             uuid.UUID  `json:"userId"`
	DeletedBy          uuid.UUID  `json:"deletedBy"`
	CollabMemberStatus string     `json:"collabMemberStatus"`
	Role               string     `json:"role"`
	MemberCreatedAt    *time.Time `json:"memberCreatedAt"`
}

// CollabJoinRequestDeletedHandler decodes CollabJoinRequestDeletedEvent deliveries and
// projects them into collab_members through the shared transactional, idempotent and
// forward-only projection.
type CollabJoinRequestDeletedHandler struct {
	projector collabMemberProjector
}

// NewCollabJoinRequestDeletedHandler creates a CollabJoinRequestDeletedHandler.
func NewCollabJoinRequestDeletedHandler(transactor CollabMemberTransactor, idempotency CollabMemberIdempotencyRepository, uc CollabMemberUsecase, logger *slog.Logger) *CollabJoinRequestDeletedHandler {
	return &CollabJoinRequestDeletedHandler{projector: collabMemberProjector{transactor: transactor, idempotency: idempotency, usecase: uc, logger: logger}}
}

// Handle implements dispatch.EventHandlerFunc.
func (h *CollabJoinRequestDeletedHandler) Handle(ctx context.Context, msg *message.Message) error {
	var event CollabJoinRequestDeletedEvent
	if err := json.Unmarshal(msg.Payload, &event); err != nil {
		eventlog.WarnInvalid(ctx, h.projector.logger, CollabJoinRequestDeletedEventType, msg.Payload, err)
		return rmqerror.NewPermanent(fmt.Errorf("decode collab join request deleted event: %w", err))
	}

	envelope := event.envelope()
	if err := validateCollabJoinRequestDeletedEvent(event, envelope); err != nil {
		eventlog.WarnInvalid(ctx, h.projector.logger, CollabJoinRequestDeletedEventType, msg.Payload, err)
		return rmqerror.NewPermanent(err)
	}

	return h.projector.project(ctx, "collab join request deleted", CollabJoinRequestDeletedEventType, envelope, collabMemberInput(envelope, event.MemberCreatedAt))
}

func (e CollabJoinRequestDeletedEvent) envelope() collabMemberEnvelope {
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

func validateCollabJoinRequestDeletedEvent(_ CollabJoinRequestDeletedEvent, envelope collabMemberEnvelope) error {
	return validateCollabMemberEnvelope("collab join request deleted", envelope, "collabMemberStatus", usecase.CollabMemberStatusDeleted)
}
