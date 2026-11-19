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

// CollabMemberLeftEventType identifies this event for idempotency bookkeeping.
const CollabMemberLeftEventType = "CollabMemberLeftEvent"

// CollabMemberLeftEvent is the wire shape of CollabMemberLeftEvent published by post-
// command-service (collab/infrastructure/events/CollabMemberLeftEvent.java) on
// rk.post.collab.member.left. Field names are camelCase, matching the Java record;
// collabMemberStatus is always LEFT. CreatedAt is the member row's creation date (the
// request's), used only when this event creates the row; it falls back to occurredAt
// when absent.
type CollabMemberLeftEvent struct {
	ID                 uuid.UUID  `json:"id"`
	CorrelationID      uuid.UUID  `json:"correlationId"`
	OccurredAt         time.Time  `json:"occurredAt"`
	CollabID           uuid.UUID  `json:"collabId"`
	UserID             uuid.UUID  `json:"userId"`
	CollabMemberStatus string     `json:"collabMemberStatus"`
	Role               string     `json:"role"`
	CreatedAt          *time.Time `json:"createdAt"`
}

// CollabMemberLeftHandler decodes CollabMemberLeftEvent deliveries and projects them
// into collab_members through the shared transactional, idempotent and forward-only
// projection.
type CollabMemberLeftHandler struct {
	projector collabMemberProjector
}

// NewCollabMemberLeftHandler creates a CollabMemberLeftHandler.
func NewCollabMemberLeftHandler(transactor CollabMemberTransactor, idempotency CollabMemberIdempotencyRepository, uc CollabMemberUsecase, logger *slog.Logger) *CollabMemberLeftHandler {
	return &CollabMemberLeftHandler{projector: collabMemberProjector{transactor: transactor, idempotency: idempotency, usecase: uc, logger: logger}}
}

// Handle implements dispatch.EventHandlerFunc.
func (h *CollabMemberLeftHandler) Handle(ctx context.Context, msg *message.Message) error {
	var event CollabMemberLeftEvent
	if err := json.Unmarshal(msg.Payload, &event); err != nil {
		eventlog.WarnInvalid(ctx, h.projector.logger, CollabMemberLeftEventType, msg.Payload, err)
		return rmqerror.NewPermanent(fmt.Errorf("decode collab member left event: %w", err))
	}

	envelope := event.envelope()
	if err := validateCollabMemberLeftEvent(event, envelope); err != nil {
		eventlog.WarnInvalid(ctx, h.projector.logger, CollabMemberLeftEventType, msg.Payload, err)
		return rmqerror.NewPermanent(err)
	}

	return h.projector.project(ctx, "collab member left", CollabMemberLeftEventType, envelope, collabMemberInput(envelope, event.CreatedAt))
}

func (e CollabMemberLeftEvent) envelope() collabMemberEnvelope {
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

func validateCollabMemberLeftEvent(_ CollabMemberLeftEvent, envelope collabMemberEnvelope) error {
	return validateCollabMemberEnvelope("collab member left", envelope, "collabMemberStatus", usecase.CollabMemberStatusLeft)
}
