package outbox

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"
	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"
)

// collectionName is the MongoDB collection storing outbox events.
const collectionName = "outbox_events"

// processedRetention is how long PROCESSED events are kept before the TTL
// index deletes them.
const processedRetention = 7 * 24 * time.Hour

// operationTimeout bounds every relay-side Mongo call.
const operationTimeout = 5 * time.Second

// Store persists outbox events in MongoDB.
type Store struct {
	collection *mongo.Collection
}

// NewStore creates a Store backed by db's outbox_events collection. Call
// EnsureIndexes once at startup.
func NewStore(db *mongo.Database) *Store {
	return &Store{collection: db.Collection(collectionName)}
}

// EnsureIndexes creates the index the relay claims through and the TTL index
// that cleans up processed events.
func (s *Store) EnsureIndexes(ctx context.Context) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	_, err := s.collection.Indexes().CreateMany(ctx, []mongo.IndexModel{
		{
			Keys:    bson.D{{Key: "status", Value: 1}, {Key: "created_at", Value: 1}},
			Options: options.Index().SetName("status_created_at"),
		},
		{
			Keys: bson.D{{Key: "processed_at", Value: 1}},
			Options: options.Index().
				SetName("processed_at_ttl").
				SetExpireAfterSeconds(int32(processedRetention.Seconds())),
		},
	})
	if err != nil {
		return fmt.Errorf("ensure outbox_events indexes: %w", err)
	}

	return nil
}

// Add inserts event. Pass the context of the Mongo transaction that changes
// state (see database.WithTransaction) so both writes commit or abort
// together; outside a transaction they are two independent writes.
func (s *Store) Add(ctx context.Context, event Event) error {
	if _, err := s.collection.InsertOne(ctx, event); err != nil {
		return fmt.Errorf("add outbox event %s: %w", event.EventType, err)
	}
	return nil
}

// ClaimNext atomically moves the oldest PENDING event (or a PROCESSING one
// whose lock is older than lockTimeout, i.e. its relay died mid-publish) to
// PROCESSING and returns it. findOneAndUpdate guarantees that concurrent
// relays never claim the same event. It returns nil when nothing is
// claimable.
func (s *Store) ClaimNext(ctx context.Context, lockTimeout time.Duration) (*Event, error) {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	now := time.Now().UTC()
	filter := bson.D{{Key: "$or", Value: bson.A{
		bson.D{{Key: "status", Value: StatusPending}},
		bson.D{
			{Key: "status", Value: StatusProcessing},
			{Key: "locked_at", Value: bson.D{{Key: "$lt", Value: now.Add(-lockTimeout)}}},
		},
	}}}
	update := bson.D{
		{Key: "$set", Value: bson.D{{Key: "status", Value: StatusProcessing}, {Key: "locked_at", Value: now}}},
		{Key: "$inc", Value: bson.D{{Key: "attempts", Value: 1}}},
	}
	opts := options.FindOneAndUpdate().
		SetSort(bson.D{{Key: "created_at", Value: 1}}).
		SetReturnDocument(options.After)

	var event Event
	err := s.collection.FindOneAndUpdate(ctx, filter, update, opts).Decode(&event)
	if errors.Is(err, mongo.ErrNoDocuments) {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("claim outbox event: %w", err)
	}

	return &event, nil
}

// MarkProcessed records a broker-confirmed publish.
func (s *Store) MarkProcessed(ctx context.Context, id uuid.UUID) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	update := bson.D{
		{Key: "$set", Value: bson.D{{Key: "status", Value: StatusProcessed}, {Key: "processed_at", Value: time.Now().UTC()}}},
		{Key: "$unset", Value: bson.D{{Key: "locked_at", Value: ""}, {Key: "last_error", Value: ""}}},
	}
	if _, err := s.collection.UpdateByID(ctx, id, update); err != nil {
		return fmt.Errorf("mark outbox event %s processed: %w", id, err)
	}
	return nil
}

// MarkFailedAttempt releases a failed claim with status (PENDING to retry, or
// FAILED once attempts are exhausted) and records lastError.
func (s *Store) MarkFailedAttempt(ctx context.Context, id uuid.UUID, status Status, lastError string) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	update := bson.D{
		{Key: "$set", Value: bson.D{{Key: "status", Value: status}, {Key: "last_error", Value: lastError}}},
		{Key: "$unset", Value: bson.D{{Key: "locked_at", Value: ""}}},
	}
	if _, err := s.collection.UpdateByID(ctx, id, update); err != nil {
		return fmt.Errorf("mark outbox event %s failed attempt: %w", id, err)
	}
	return nil
}
