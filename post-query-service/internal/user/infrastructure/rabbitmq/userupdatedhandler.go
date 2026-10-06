package rabbitmq

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"time"

	"github.com/ThreeDotsLabs/watermill/message"
	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/user/application/usecase"
)

// UserUpdatedEventType identifies this event for idempotency bookkeeping.
const UserUpdatedEventType = "UserUpdatedEvent"

// UserUpdatedBio is the wire shape of UserUpdatedEvent's nested bio object.
type UserUpdatedBio struct {
	Description string            `json:"description"`
	SocialMedia map[string]string `json:"socialMedia"`
}

// UserUpdatedEvent is the wire shape of UserUpdatedEvent published by
// social-service. Field names are camelCase, matching the real Java
// UserUpdatedEvent record.
type UserUpdatedEvent struct {
	ID            uuid.UUID      `json:"id"`
	CorrelationID uuid.UUID      `json:"correlationId"`
	OccurredAt    time.Time      `json:"occurredAt"`
	UserID        uuid.UUID      `json:"userId"`
	Username      string         `json:"username"`
	Email         string         `json:"email"`
	Bio           UserUpdatedBio `json:"bio"`
	PictureURL    string         `json:"pictureUrl"`
	AccountStatus string         `json:"accountStatus"`
	CreatedAt     time.Time      `json:"createdAt"`
	UpdatedAt     time.Time      `json:"updatedAt"`
}

// UserUpdatedIdempotencyRepository records and checks processed events.
type UserUpdatedIdempotencyRepository interface {
	IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error)
	MarkProcessed(ctx context.Context, eventID, correlationID uuid.UUID, eventType string) error
}

// UserUpdatedUsecase executes the user update projection.
type UserUpdatedUsecase interface {
	Execute(ctx context.Context, input usecase.UpdateUserInput) error
}

// UserUpdatedHandler decodes UserUpdatedEvent deliveries, enforces
// idempotency, and delegates to UserUpdatedUsecase.
type UserUpdatedHandler struct {
	idempotency UserUpdatedIdempotencyRepository
	usecase     UserUpdatedUsecase
	logger      *slog.Logger
}

// NewUserUpdatedHandler creates a UserUpdatedHandler.
func NewUserUpdatedHandler(idempotency UserUpdatedIdempotencyRepository, uc UserUpdatedUsecase, logger *slog.Logger) *UserUpdatedHandler {
	return &UserUpdatedHandler{idempotency: idempotency, usecase: uc, logger: logger}
}

// Handle implements dispatch.EventHandlerFunc.
func (h *UserUpdatedHandler) Handle(ctx context.Context, msg *message.Message) error {
	var event UserUpdatedEvent
	if err := json.Unmarshal(msg.Payload, &event); err != nil {
		return rmqerror.NewPermanent(fmt.Errorf("decode user updated event: %w", err))
	}

	if err := validateUserUpdatedEvent(event); err != nil {
		return rmqerror.NewPermanent(err)
	}

	alreadyProcessed, err := h.idempotency.IsProcessed(ctx, event.ID)
	if err != nil {
		return fmt.Errorf("check user updated event %s processed: %w", event.ID, err)
	}
	if alreadyProcessed {
		h.logger.WarnContext(ctx, "discarding duplicate user updated event", "event_id", event.ID)
		return nil
	}

	input := usecase.UpdateUserInput{
		UserID:         event.UserID,
		Username:       event.Username,
		Email:          event.Email,
		BioDescription: event.Bio.Description,
		BioSocialMedia: event.Bio.SocialMedia,
		PictureURL:     event.PictureURL,
		AccountStatus:  event.AccountStatus,
		UpdatedAt:      event.UpdatedAt,
	}
	if err := h.usecase.Execute(ctx, input); err != nil {
		return fmt.Errorf("execute update user usecase for event %s: %w", event.ID, err)
	}

	if err := h.idempotency.MarkProcessed(ctx, event.ID, event.CorrelationID, UserUpdatedEventType); err != nil {
		return fmt.Errorf("mark user updated event %s processed: %w", event.ID, err)
	}

	return nil
}

func validateUserUpdatedEvent(event UserUpdatedEvent) error {
	if event.ID == uuid.Nil {
		return fmt.Errorf("user updated event: id is required")
	}
	if event.CorrelationID == uuid.Nil {
		return fmt.Errorf("user updated event: correlationId is required")
	}
	if event.OccurredAt.IsZero() {
		return fmt.Errorf("user updated event: occurredAt is required")
	}
	if event.UserID == uuid.Nil {
		return fmt.Errorf("user updated event: userId is required")
	}
	if event.Username == "" {
		return fmt.Errorf("user updated event: username is required")
	}

	return nil
}
