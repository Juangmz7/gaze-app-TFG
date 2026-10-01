// Package feeddeleted publishes UserFeedDeletedEvent to the feed events
// exchange. There is no existing trigger (HTTP endpoint or use case) that
// deletes a feed yet; this package only provides the publisher component so
// a future use case can call it once that trigger exists (feature_list.json
// task 41 scope is the publisher itself, not its caller).
package feeddeleted

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/ThreeDotsLabs/watermill"
	"github.com/ThreeDotsLabs/watermill/message"
	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/topology"
)

// Event is the UserFeedDeletedEvent payload published to
// topology.ExchangeFeedEvents with routing key topology.RKFeedDeleted.
type Event struct {
	ID            uuid.UUID `json:"id"`
	CorrelationID uuid.UUID `json:"correlation_id"`
	OccurredAt    time.Time `json:"occurred_at"`
	UserID        uuid.UUID `json:"user_id"`
}

// Publisher publishes Event messages through a Watermill message.Publisher
// configured for topology.ExchangeFeedEvents (see
// router.NewPublisherConfig).
type Publisher struct {
	publisher message.Publisher
}

// NewPublisher creates a Publisher backed by publisher.
func NewPublisher(publisher message.Publisher) *Publisher {
	return &Publisher{publisher: publisher}
}

// Publish marshals event and publishes it to topology.ExchangeFeedEvents
// using topology.RKFeedDeleted as the routing key/Watermill topic.
func (p *Publisher) Publish(ctx context.Context, event Event) error {
	if event.ID == uuid.Nil {
		return fmt.Errorf("publish user feed deleted event: id is required")
	}
	if event.UserID == uuid.Nil {
		return fmt.Errorf("publish user feed deleted event: user_id is required")
	}

	payload, err := json.Marshal(event)
	if err != nil {
		return fmt.Errorf("publish user feed deleted event: marshal payload: %w", err)
	}

	msg := message.NewMessage(watermill.NewUUID(), payload)
	msg.SetContext(ctx)

	if err := p.publisher.Publish(topology.RKFeedDeleted, msg); err != nil {
		return fmt.Errorf("publish user feed deleted event: %w", err)
	}

	return nil
}
