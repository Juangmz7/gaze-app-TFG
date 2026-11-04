// Package publisher enqueues UserFeedExhaustedEvent in the transactional
// outbox; outbox.Relay delivers it to the feed events exchange. There is no
// existing trigger (HTTP endpoint or use case) that deletes a feed yet; this
// package only provides the publisher component so a future use case can
// call it once that trigger exists (feature_list.json task 41 scope is the
// publisher itself, not its caller).
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
// JSON field names follow the camelCase contract shared by every service.
type Event struct {
	ID            uuid.UUID `json:"id"`
	CorrelationID uuid.UUID `json:"correlationId"`
	OccurredAt    time.Time `json:"occurredAt"`
	UserID        uuid.UUID `json:"userId"`
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
	if event.UserID == uuid.Nil {
		return fmt.Errorf("publish user feed exhausted event: userId is required")
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
