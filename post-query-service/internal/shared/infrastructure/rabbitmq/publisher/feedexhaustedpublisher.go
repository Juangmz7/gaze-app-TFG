// Package publisher enqueues UserFeedExhaustedEvent in the transactional outbox;
// outbox.Relay delivers it to the feed events exchange, where recommendation-service
// answers with RecommendedPostSent. One Publisher is created in main from the shared
// outbox store: it is called from feed.RequestFeedPrefetchUsecase, inside the
// transaction that takes the user's prefetch lease.
package publisher

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/outbox"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/topology"
)

// EventType is the outbox event type recorded for UserFeedExhaustedEvent.
const EventType = "UserFeedExhaustedEvent"

// Event is the UserFeedExhaustedEvent payload published to
// topology.ExchangeFeedEvents with routing key topology.RKFeedExhausted.
// JSON field names follow the camelCase contract shared by every service, and
// recommendation-service reads exactly these. CorrelationID is the prefetch lease
// token: the response echoes it, and only a matching response releases the lease.
// Reason is disconnect, stale_heartbeat, user_registered or low_watermark.
type Event struct {
	ID              uuid.UUID   `json:"id"`
	CorrelationID   uuid.UUID   `json:"correlationId"`
	OccurredAt      time.Time   `json:"occurredAt"`
	UserID          uuid.UUID   `json:"userId"`
	Reason          string      `json:"reason"`
	CurrentFeedSize int64       `json:"currentFeedSize"`
	ExcludedPostIDs []uuid.UUID `json:"excludedPostIds"`
}

// OutboxWriter stores outbox events (implemented by outbox.Store).
type OutboxWriter interface {
	Add(ctx context.Context, event outbox.Event) error
}

// Publisher writes Event messages to the outbox.
type Publisher struct {
	outbox OutboxWriter
}

// NewPublisher creates a Publisher backed by outboxWriter.
func NewPublisher(outboxWriter OutboxWriter) *Publisher {
	return &Publisher{outbox: outboxWriter}
}

// Publish stores event in the outbox (message id = event.ID). Call it with
// the ctx of the database.WithTransaction that changes the related state, so
// the event exists if and only if that change commits.
func (p *Publisher) Publish(ctx context.Context, event Event) error {
	if event.ID == uuid.Nil {
		return fmt.Errorf("publish user feed exhausted event: id is required")
	}
	if event.CorrelationID == uuid.Nil {
		return fmt.Errorf("publish user feed exhausted event: correlationId is required")
	}
	if event.UserID == uuid.Nil {
		return fmt.Errorf("publish user feed exhausted event: userId is required")
	}
	if event.Reason == "" {
		return fmt.Errorf("publish user feed exhausted event: reason is required")
	}
	if event.CurrentFeedSize < 0 {
		return fmt.Errorf("publish user feed exhausted event: currentFeedSize %d is negative", event.CurrentFeedSize)
	}
	if event.ExcludedPostIDs == nil {
		event.ExcludedPostIDs = []uuid.UUID{}
	}

	outboxEvent, err := outbox.NewPendingEvent(
		event.ID, event.CorrelationID, EventType,
		topology.ExchangeFeedEvents, topology.RKFeedExhausted, event,
	)
	if err != nil {
		return fmt.Errorf("publish user feed exhausted event: %w", err)
	}

	if err := p.outbox.Add(ctx, outboxEvent); err != nil {
		return fmt.Errorf("publish user feed exhausted event: %w", err)
	}

	return nil
}
