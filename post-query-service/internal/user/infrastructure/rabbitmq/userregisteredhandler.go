// Package rabbitmq handles UserRegisteredEvent deliveries from
// topology.ExchangeUserEvents (routing key rk.user.registered, consumed via
// topology.QueueUserFast).
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
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/user/application/usecase"
)

// UserRegisteredEventType identifies this event for idempotency bookkeeping.
const UserRegisteredEventType = "UserRegisteredEvent"

// UserRegisteredBioPayload is the wire shape of the Java UserBioEventPayload
// record.
type UserRegisteredBioPayload struct {
	Description string            `json:"description"`
	SocialMedia map[string]string `json:"socialMedia"`
}

// UserRegisteredEvent is the wire shape of UserRegisteredEvent published by
// social-service. Field names are camelCase, matching the real Java record. It
// carries no createdAt, so occurredAt is projected as the user's creation time,
// and no picture: picture_url stays empty until a UserUpdatedEvent sets it.
type UserRegisteredEvent struct {
	ID            uuid.UUID                `json:"id"`
	CorrelationID uuid.UUID                `json:"correlationId"`
	OccurredAt    time.Time                `json:"occurredAt"`
	UserID        uuid.UUID                `json:"userId"`
	Username      string                   `json:"username"`
	Email         string                   `json:"email"`
	Bio           UserRegisteredBioPayload `json:"bio"`
}

// UserRegisteredIdempotencyRepository records and checks processed events.
type UserRegisteredIdempotencyRepository interface {
	IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error)
	MarkProcessed(ctx context.Context, eventID, correlationID uuid.UUID, eventType string) error
}

// UserRegisteredUsecase executes the user registration projection.
type UserRegisteredUsecase interface {
	Execute(ctx context.Context, input usecase.RegisterUserInput) error
}

// UserRegisteredHandler decodes UserRegisteredEvent deliveries, enforces idempotency, and
// delegates to UserRegisteredUsecase.
type UserRegisteredHandler struct {
	idempotency UserRegisteredIdempotencyRepository
	usecase     UserRegisteredUsecase
	logger      *slog.Logger
}

// NewUserRegisteredHandler creates a UserRegisteredHandler.
func NewUserRegisteredHandler(idempotency UserRegisteredIdempotencyRepository, uc UserRegisteredUsecase, logger *slog.Logger) *UserRegisteredHandler {
	return &UserRegisteredHandler{idempotency: idempotency, usecase: uc, logger: logger}
}

// Handle implements dispatch.EventHandlerFunc.
func (h *UserRegisteredHandler) Handle(ctx context.Context, msg *message.Message) error {
	var event UserRegisteredEvent
	if err := json.Unmarshal(msg.Payload, &event); err != nil {
		eventlog.WarnInvalid(ctx, h.logger, UserRegisteredEventType, msg.Payload, err)
		return rmqerror.NewPermanent(fmt.Errorf("decode user registered event: %w", err))
	}

	if err := validateUserRegisteredEvent(event); err != nil {
		eventlog.WarnInvalid(ctx, h.logger, UserRegisteredEventType, msg.Payload, err)
		return rmqerror.NewPermanent(err)
	}

	alreadyProcessed, err := h.idempotency.IsProcessed(ctx, event.ID)
	if err != nil {
		return fmt.Errorf("check user registered event %s processed: %w", event.ID, err)
	}
	if alreadyProcessed {
		h.logger.WarnContext(ctx, "discarding duplicate user registered event", "event_id", event.ID, "correlation_id", event.CorrelationID, "userId", event.UserID)
		return nil
	}

	input := usecase.RegisterUserInput{
		UserID:         event.UserID,
		Username:       event.Username,
		Email:          event.Email,
		BioDescription: event.Bio.Description,
		BioSocialMedia: event.Bio.SocialMedia,
		CreatedAt:      event.OccurredAt,
		CorrelationID:  event.CorrelationID.String(),
	}
	if err := h.usecase.Execute(ctx, input); err != nil {
		return fmt.Errorf("execute register user usecase for event %s: %w", event.ID, err)
	}

	if err := h.idempotency.MarkProcessed(ctx, event.ID, event.CorrelationID, UserRegisteredEventType); err != nil {
		return fmt.Errorf("mark user registered event %s processed: %w", event.ID, err)
	}

	h.logger.InfoContext(ctx, "user registered event projected", "event_id", event.ID, "correlation_id", event.CorrelationID, "userId", event.UserID)

	return nil
}

func validateUserRegisteredEvent(event UserRegisteredEvent) error {
	if event.ID == uuid.Nil {
		return fmt.Errorf("user registered event: id is required")
	}
	if event.CorrelationID == uuid.Nil {
		return fmt.Errorf("user registered event: correlationId is required")
	}
	if event.OccurredAt.IsZero() {
		return fmt.Errorf("user registered event: occurredAt is required")
	}
	if event.UserID == uuid.Nil {
		return fmt.Errorf("user registered event: userId is required")
	}
	if event.Username == "" {
		return fmt.Errorf("user registered event: username is required")
	}

	return nil
}
