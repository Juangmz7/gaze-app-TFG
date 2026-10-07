// Package rabbitmq handles UserFollowedEvent and UserUnfollowedEvent
// deliveries from topology.ExchangeUserEvents (routing keys
// rk.user.follow.created via topology.QueueUserFast and
// rk.user.follow.deleted via topology.QueueUserSlow).
package rabbitmq

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"time"

	"github.com/ThreeDotsLabs/watermill/message"
	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/follow/application/usecase"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
)

// UserFollowCreatedEventType identifies this event for idempotency
// bookkeeping.
const UserFollowCreatedEventType = "UserFollowedEvent"

// UserFollowCreatedEvent is the wire shape of UserFollowedEvent published
// by social-service. Field names are camelCase, matching the real Java
// UserFollowedEvent record.
type UserFollowCreatedEvent struct {
	ID             uuid.UUID `json:"id"`
	CorrelationID  uuid.UUID `json:"correlationId"`
	OccurredAt     time.Time `json:"occurredAt"`
	FollowerUserID uuid.UUID `json:"followerUserId"`
	FollowedUserID uuid.UUID `json:"followedUserId"`
}

// UserFollowCreatedIdempotencyRepository records and checks processed
// events.
type UserFollowCreatedIdempotencyRepository interface {
	IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error)
	MarkProcessed(ctx context.Context, eventID, correlationID uuid.UUID, eventType string) error
}

// UserFollowCreatedUsecase executes the follow creation projection.
type UserFollowCreatedUsecase interface {
	Execute(ctx context.Context, input usecase.CreateFollowInput) error
}

// UserFollowCreatedHandler decodes UserFollowCreatedEvent deliveries,
// enforces idempotency, and delegates to UserFollowCreatedUsecase.
type UserFollowCreatedHandler struct {
	idempotency UserFollowCreatedIdempotencyRepository
	usecase     UserFollowCreatedUsecase
	logger      *slog.Logger
}

// NewUserFollowCreatedHandler creates a UserFollowCreatedHandler.
func NewUserFollowCreatedHandler(idempotency UserFollowCreatedIdempotencyRepository, uc UserFollowCreatedUsecase, logger *slog.Logger) *UserFollowCreatedHandler {
	return &UserFollowCreatedHandler{idempotency: idempotency, usecase: uc, logger: logger}
}

// Handle implements dispatch.EventHandlerFunc.
func (h *UserFollowCreatedHandler) Handle(ctx context.Context, msg *message.Message) error {
	var event UserFollowCreatedEvent
	if err := json.Unmarshal(msg.Payload, &event); err != nil {
		return rmqerror.NewPermanent(fmt.Errorf("decode user follow created event: %w", err))
	}

	if err := validateUserFollowCreatedEvent(event); err != nil {
		return rmqerror.NewPermanent(err)
	}

	alreadyProcessed, err := h.idempotency.IsProcessed(ctx, event.ID)
	if err != nil {
		return fmt.Errorf("check user follow created event %s processed: %w", event.ID, err)
	}
	if alreadyProcessed {
		h.logger.WarnContext(ctx, "discarding duplicate user follow created event", "event_id", event.ID)
		return nil
	}

	input := usecase.CreateFollowInput{
		FollowerUserID: event.FollowerUserID,
		FollowedUserID: event.FollowedUserID,
		CreatedAt:      event.OccurredAt,
	}
	if err := h.usecase.Execute(ctx, input); err != nil {
		return fmt.Errorf("execute create follow usecase for event %s: %w", event.ID, err)
	}

	if err := h.idempotency.MarkProcessed(ctx, event.ID, event.CorrelationID, UserFollowCreatedEventType); err != nil {
		return fmt.Errorf("mark user follow created event %s processed: %w", event.ID, err)
	}

	return nil
}

func validateUserFollowCreatedEvent(event UserFollowCreatedEvent) error {
	if event.ID == uuid.Nil {
		return fmt.Errorf("user follow created event: id is required")
	}
	if event.CorrelationID == uuid.Nil {
		return fmt.Errorf("user follow created event: correlationId is required")
	}
	if event.OccurredAt.IsZero() {
		return fmt.Errorf("user follow created event: occurredAt is required")
	}
	if event.FollowerUserID == uuid.Nil {
		return fmt.Errorf("user follow created event: followerUserId is required")
	}
	if event.FollowedUserID == uuid.Nil {
		return fmt.Errorf("user follow created event: followedUserId is required")
	}

	return nil
}
