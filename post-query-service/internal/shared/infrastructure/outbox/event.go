// Package outbox implements the transactional outbox for post-query-service:
// events are stored in MongoDB in the same transaction as the state change
// that produced them, and a relay publishes them to RabbitMQ with publisher
// confirms (at-least-once delivery, consumers deduplicate by message id).
package outbox

import (
	"encoding/json"
	"fmt"
	"time"

	"github.com/google/uuid"
)

// Status is the lifecycle state of an outbox event:
// PENDING -> PROCESSING -> PROCESSED, or FAILED once attempts are exhausted.
type Status string

const (
	StatusPending    Status = "PENDING"
	StatusProcessing Status = "PROCESSING"
	StatusProcessed  Status = "PROCESSED"
	StatusFailed     Status = "FAILED"
)

// Event is one outbox_events document. Exchange and RoutingKey are stored at
// insert time so the relay stays generic.
type Event struct {
	ID            uuid.UUID  `bson:"_id"`
	CorrelationID uuid.UUID  `bson:"correlation_id"`
	EventType     string     `bson:"event_type"`
	Exchange      string     `bson:"exchange"`
	RoutingKey    string     `bson:"routing_key"`
	Payload       string     `bson:"payload"`
	Status        Status     `bson:"status"`
	Attempts      int        `bson:"attempts"`
	LastError     string     `bson:"last_error,omitempty"`
	CreatedAt     time.Time  `bson:"created_at"`
	LockedAt      *time.Time `bson:"locked_at,omitempty"`
	// ProcessedAt is only set once PROCESSED, so the TTL index never expires
	// unfinished events.
	ProcessedAt *time.Time `bson:"processed_at,omitempty"`
}

// NewPendingEvent builds a PENDING outbox event whose payload is payload
// serialized as JSON.
func NewPendingEvent(id, correlationID uuid.UUID, eventType, exchange, routingKey string, payload any) (Event, error) {
	body, err := json.Marshal(payload)
	if err != nil {
		return Event{}, fmt.Errorf("new outbox event %s: marshal payload: %w", eventType, err)
	}

	return Event{
		ID:            id,
		CorrelationID: correlationID,
		EventType:     eventType,
		Exchange:      exchange,
		RoutingKey:    routingKey,
		Payload:       string(body),
		Status:        StatusPending,
		CreatedAt:     time.Now().UTC(),
	}, nil
}
