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

// UserFollowDeletedEventType identifies this event for idempotency
// bookkeeping.
const UserFollowDeletedEventType = "UserUnfollowedEvent"

// UserFollowDeletedEvent is the wire shape of UserUnfollowedEvent
// published by social-service. Field names are camelCase, matching the
// real Java UserUnfollowedEvent record.
type UserFollowDeletedEvent struct {
	ID             uuid.UUID `json:"id"`
	CorrelationID  uuid.UUID `json:"correlationId"`
	OccurredAt     time.Time `json:"occurredAt"`
	FollowerUserID uuid.UUID `json:"followerUserId"`
	FollowedUserID uuid.UUID `json:"followedUserId"`
}

// UserFollowDeletedIdempotencyRepository records and checks processed
// events.
type UserFollowDeletedIdempotencyRepository interface {
	IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error)
	MarkProcessed(ctx context.Context, eventID, correlationID uuid.UUID, eventType string) error
}

// UserFollowDeletedUsecase executes the follow deletion projection.
type UserFollowDeletedUsecase interface {
	Execute(ctx context.Context, input usecase.DeleteFollowInput) error
}

// UserFollowDeletedHandler decodes UserFollowDeletedEvent deliveries,
// enforces idempotency, and delegates to UserFollowDeletedUsecase.
type UserFollowDeletedHandler struct {
	idempotency UserFollowDeletedIdempotencyRepository
	usecase     UserFollowDeletedUsecase
	logger      *slog.Logger
}

// NewUserFollowDeletedHandler creates a UserFollowDeletedHandler.
func NewUserFollowDeletedHandler(idempotency UserFollowDeletedIdempotencyRepository, uc UserFollowDeletedUsecase, logger *slog.Logger) *UserFollowDeletedHandler {
	return &UserFollowDeletedHandler{idempotency: idempotency, usecase: uc, logger: logger}
}

// Handle implements dispatch.EventHandlerFunc.
func (h *UserFollowDeletedHandler) Handle(ctx context.Context, msg *message.Message) error {
	var event UserFollowDeletedEvent
	if err := json.Unmarshal(msg.Payload, &event); err != nil {
		return rmqerror.NewPermanent(fmt.Errorf("decode user follow deleted event: %w", err))
	}

	if err := validateUserFollowDeletedEvent(event); err != nil {
		return rmqerror.NewPermanent(err)
	}

	alreadyProcessed, err := h.idempotency.IsProcessed(ctx, event.ID)
	if err != nil {
		return fmt.Errorf("check user follow deleted event %s processed: %w", event.ID, err)
	}
	if alreadyProcessed {
		h.logger.WarnContext(ctx, "discarding duplicate user follow deleted event", "event_id", event.ID)
		return nil
	}

	input := usecase.DeleteFollowInput{
		FollowerUserID: event.FollowerUserID,
		FollowedUserID: event.FollowedUserID,
	}
	if err := h.usecase.Execute(ctx, input); err != nil {
		return fmt.Errorf("execute delete follow usecase for event %s: %w", event.ID, err)
	}

	if err := h.idempotency.MarkProcessed(ctx, event.ID, event.CorrelationID, UserFollowDeletedEventType); err != nil {
		return fmt.Errorf("mark user follow deleted event %s processed: %w", event.ID, err)
	}

	return nil
}

func validateUserFollowDeletedEvent(event UserFollowDeletedEvent) error {
	if event.ID == uuid.Nil {
		return fmt.Errorf("user follow deleted event: id is required")
	}
	if event.CorrelationID == uuid.Nil {
		return fmt.Errorf("user follow deleted event: correlationId is required")
	}
	if event.OccurredAt.IsZero() {
		return fmt.Errorf("user follow deleted event: occurredAt is required")
	}
	if event.FollowerUserID == uuid.Nil {
		return fmt.Errorf("user follow deleted event: followerUserId is required")
	}
	if event.FollowedUserID == uuid.Nil {
		return fmt.Errorf("user follow deleted event: followedUserId is required")
	}

	return nil
}
