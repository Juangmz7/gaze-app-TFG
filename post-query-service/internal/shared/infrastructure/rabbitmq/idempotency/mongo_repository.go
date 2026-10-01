// Package idempotency persists which RabbitMQ events post-query-service has
// already processed, so a redelivered or duplicated message can be
// recognized and skipped without calling its use case again.
package idempotency

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"
)

// collectionName is the MongoDB collection storing processed event records.
const collectionName = "processed_events"

// operationTimeout bounds every Mongo call made by Repository.
const operationTimeout = 5 * time.Second

// processedEvent is the document shape stored per processed event.
type processedEvent struct {
	EventID       uuid.UUID `bson:"event_id"`
	CorrelationID uuid.UUID `bson:"correlation_id"`
	EventType     string    `bson:"event_type"`
	ProcessedAt   time.Time `bson:"processed_at"`
}

// Repository checks and records processed events in MongoDB, using a unique
// index on event_id to guarantee idempotency even under concurrent
// deliveries of the same event.
type Repository struct {
	collection *mongo.Collection
}

// NewRepository creates a Repository backed by db's processed_events
// collection. Call EnsureIndexes once at startup before consuming messages.
func NewRepository(db *mongo.Database) *Repository {
	return &Repository{collection: db.Collection(collectionName)}
}

// EnsureIndexes creates the unique index on event_id required to make
// MarkProcessed race-safe under concurrent deliveries of the same event.
func (r *Repository) EnsureIndexes(ctx context.Context) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	indexModel := mongo.IndexModel{
		Keys:    bson.D{{Key: "event_id", Value: 1}},
		Options: options.Index().SetUnique(true),
	}

	if _, err := r.collection.Indexes().CreateOne(ctx, indexModel); err != nil {
		return fmt.Errorf("ensure processed_events indexes: %w", err)
	}

	return nil
}

// IsProcessed reports whether eventID has already been recorded as
// processed.
func (r *Repository) IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error) {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	err := r.collection.FindOne(ctx, bson.D{{Key: "event_id", Value: eventID}}).Err()
	if err == nil {
		return true, nil
	}
	if err == mongo.ErrNoDocuments {
		return false, nil
	}

	return false, fmt.Errorf("check processed event %s: %w", eventID, err)
}

// MarkProcessed records eventID as processed. It is idempotent: if eventID
// was already recorded (e.g. a concurrent delivery raced this one), the
// unique index rejects the duplicate insert and MarkProcessed returns nil
// rather than an error.
func (r *Repository) MarkProcessed(ctx context.Context, eventID, correlationID uuid.UUID, eventType string) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	doc := processedEvent{
		EventID:       eventID,
		CorrelationID: correlationID,
		EventType:     eventType,
		ProcessedAt:   time.Now().UTC(),
	}

	_, err := r.collection.InsertOne(ctx, doc)
	if err == nil {
		return nil
	}
	if mongo.IsDuplicateKeyError(err) {
		return nil
	}

	return fmt.Errorf("mark event %s processed: %w", eventID, err)
}
