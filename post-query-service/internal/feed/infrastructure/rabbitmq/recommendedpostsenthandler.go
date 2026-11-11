package rabbitmq

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"time"

	"github.com/ThreeDotsLabs/watermill/message"
	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/eventlog"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
)

// RecommendedPostSentEventType identifies this event for idempotency bookkeeping.
const RecommendedPostSentEventType = "RecommendedPostSent"

// RecommendedPostSentEvent is the wire shape of RecommendedPostSentEvent, published by
// recommendation-service on topology.ExchangeFeedEvents with routing key
// topology.RKPostRecommendedSent. correlationId echoes the request's, which is the
// lease token. posts holds post ids, best first; an empty list is valid and a missing
// one is not.
type RecommendedPostSentEvent struct {
	ID            uuid.UUID   `json:"id"`
	CorrelationID uuid.UUID   `json:"correlationId"`
	OccurredAt    time.Time   `json:"occurredAt"`
	UserID        uuid.UUID   `json:"userId"`
	Posts         []uuid.UUID `json:"posts"`
}

// RecommendedPostSentIdempotencyRepository records and checks processed events.
type RecommendedPostSentIdempotencyRepository interface {
	IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error)
	MarkProcessed(ctx context.Context, eventID, correlationID uuid.UUID, eventType string) error
}

// RecommendedPostSentUsecase applies the response to the user's feed.
type RecommendedPostSentUsecase interface {
	Execute(ctx context.Context, input usecase.BuildUserFeedInput) (usecase.BuildUserFeedOutput, error)
}

// RecommendedPostSentHandler decodes the deliveries, enforces idempotency and
// delegates to RecommendedPostSentUsecase.
type RecommendedPostSentHandler struct {
	idempotency RecommendedPostSentIdempotencyRepository
	usecase     RecommendedPostSentUsecase
	logger      *slog.Logger
}

// NewRecommendedPostSentHandler creates a RecommendedPostSentHandler.
func NewRecommendedPostSentHandler(idempotency RecommendedPostSentIdempotencyRepository, uc RecommendedPostSentUsecase, logger *slog.Logger) *RecommendedPostSentHandler {
	return &RecommendedPostSentHandler{idempotency: idempotency, usecase: uc, logger: logger}
}

// Handle implements dispatch.EventHandlerFunc. MarkProcessed comes last, so a crash
// before it means a redelivery, which the unique indexes make harmless.
func (h *RecommendedPostSentHandler) Handle(ctx context.Context, msg *message.Message) error {
	var event RecommendedPostSentEvent
	if err := json.Unmarshal(msg.Payload, &event); err != nil {
		eventlog.WarnInvalid(ctx, h.logger, RecommendedPostSentEventType, msg.Payload, err)
		return rmqerror.NewPermanent(fmt.Errorf("decode recommended post sent event: %w", err))
	}

	if err := validateRecommendedPostSentEvent(event); err != nil {
		eventlog.WarnInvalid(ctx, h.logger, RecommendedPostSentEventType, msg.Payload, err)
		return rmqerror.NewPermanent(err)
	}

	alreadyProcessed, err := h.idempotency.IsProcessed(ctx, event.ID)
	if err != nil {
		return fmt.Errorf("check recommended post sent event %s processed: %w", event.ID, err)
	}
	if alreadyProcessed {
		h.logger.WarnContext(ctx, "discarding duplicate recommended post sent event", "event_id", event.ID, "correlation_id", event.CorrelationID, "userId", event.UserID)
		return nil
	}

	output, err := h.usecase.Execute(ctx, usecase.BuildUserFeedInput{UserID: event.UserID, PostIDs: event.Posts, LeaseToken: event.CorrelationID})
	if err != nil {
		return fmt.Errorf("execute build user feed usecase for event %s: %w", event.ID, err)
	}

	if err := h.idempotency.MarkProcessed(ctx, event.ID, event.CorrelationID, RecommendedPostSentEventType); err != nil {
		return fmt.Errorf("mark recommended post sent event %s processed: %w", event.ID, err)
	}

	h.logger.InfoContext(ctx, "recommended post sent event consumed", "event_id", event.ID, "correlation_id", event.CorrelationID, "userId", event.UserID,
		"posts", len(event.Posts), "inserted", output.Inserted, "user_gone", output.UserGone, "lease_released", output.LeaseReleased)

	return nil
}

func validateRecommendedPostSentEvent(event RecommendedPostSentEvent) error {
	if event.ID == uuid.Nil {
		return fmt.Errorf("recommended post sent event: id is required")
	}
	if event.CorrelationID == uuid.Nil {
		return fmt.Errorf("recommended post sent event: correlationId is required")
	}
	if event.OccurredAt.IsZero() {
		return fmt.Errorf("recommended post sent event: occurredAt is required")
	}
	if event.UserID == uuid.Nil {
		return fmt.Errorf("recommended post sent event: userId is required")
	}
	if event.Posts == nil {
		return fmt.Errorf("recommended post sent event: posts is required")
	}

	return nil
}
