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

// CollabMemberBannedEventType identifies this event for idempotency bookkeeping.
const CollabMemberBannedEventType = "CollabMemberBannedEvent"

// CollabMemberBannedEvent is the wire shape of CollabMemberBannedEvent published by
// post-command-service (collab/infrastructure/events/CollabMemberBannedEvent.java) on
// rk.post.collab.member.banned. Field names are camelCase, matching the Java record;
// collabMemberStatus is always BANNED. MemberCreatedAt is the member row's creation
// date (the request's), used only when this event creates the row; it falls back to
// occurredAt when absent.
type CollabMemberBannedEvent struct {
	ID                 uuid.UUID  `json:"id"`
	CorrelationID      uuid.UUID  `json:"correlationId"`
	OccurredAt         time.Time  `json:"occurredAt"`
	CollabID           uuid.UUID  `json:"collabId"`
	UserID             uuid.UUID  `json:"userId"`
	CollabMemberStatus string     `json:"collabMemberStatus"`
	Role               string     `json:"role"`
	MemberCreatedAt    *time.Time `json:"memberCreatedAt"`
}

// CollabMemberBannedHandler decodes CollabMemberBannedEvent deliveries and projects
// them into collab_members through the shared transactional, idempotent and forward-
// only projection.
type CollabMemberBannedHandler struct {
	projector collabMemberProjector
}

// NewCollabMemberBannedHandler creates a CollabMemberBannedHandler.
func NewCollabMemberBannedHandler(transactor CollabMemberTransactor, idempotency CollabMemberIdempotencyRepository, uc CollabMemberUsecase, logger *slog.Logger) *CollabMemberBannedHandler {
	return &CollabMemberBannedHandler{projector: collabMemberProjector{transactor: transactor, idempotency: idempotency, usecase: uc, logger: logger}}
}

// Handle implements dispatch.EventHandlerFunc.
func (h *CollabMemberBannedHandler) Handle(ctx context.Context, msg *message.Message) error {
	var event CollabMemberBannedEvent
	if err := json.Unmarshal(msg.Payload, &event); err != nil {
		eventlog.WarnInvalid(ctx, h.projector.logger, CollabMemberBannedEventType, msg.Payload, err)
		return rmqerror.NewPermanent(fmt.Errorf("decode collab member banned event: %w", err))
	}

	envelope := event.envelope()
	if err := validateCollabMemberBannedEvent(event, envelope); err != nil {
		eventlog.WarnInvalid(ctx, h.projector.logger, CollabMemberBannedEventType, msg.Payload, err)
		return rmqerror.NewPermanent(err)
	}

	return h.projector.project(ctx, "collab member banned", CollabMemberBannedEventType, envelope, collabMemberInput(envelope, event.MemberCreatedAt))
}

func (e CollabMemberBannedEvent) envelope() collabMemberEnvelope {
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

func validateCollabMemberBannedEvent(_ CollabMemberBannedEvent, envelope collabMemberEnvelope) error {
	return validateCollabMemberEnvelope("collab member banned", envelope, "collabMemberStatus", usecase.CollabMemberStatusBanned)
}
