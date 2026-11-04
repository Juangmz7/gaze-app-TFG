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

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/database"
)

// collectionName is the MongoDB collection storing outbox events.
const collectionName = "outbox_events"

// processedRetention is how long PROCESSED events are kept before the TTL
// index deletes them.
const processedRetention = 7 * 24 * time.Hour

// operationTimeout bounds every relay-side Mongo call.
const operationTimeout = 5 * time.Second

// ImmediateSender publishes one just-committed event without waiting for the
// next relay batch (implemented by Relay).
type ImmediateSender interface {
	RelayImmediately(ctx context.Context, id uuid.UUID)
}

// Store persists outbox events in MongoDB.
type Store struct {
	collection *mongo.Collection
	immediate  ImmediateSender
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

// SendImmediatelyWith makes Add hand every event to sender as soon as its
// transaction commits (the low-latency path); the relay batch still covers
// any event the sender could not publish. Call it once at startup, before the
// store is used.
func (s *Store) SendImmediatelyWith(sender ImmediateSender) {
	s.immediate = sender
}

// Add inserts event. Pass the context of the Mongo transaction that changes
// state (see database.WithTransaction) so both writes commit or abort
// together; outside a transaction they are two independent writes. With an
// ImmediateSender set, the event is published right after that commit.
func (s *Store) Add(ctx context.Context, event Event) error {
	if _, err := s.collection.InsertOne(ctx, event); err != nil {
		return fmt.Errorf("add outbox event %s: %w", event.EventType, err)
	}

	if s.immediate != nil {
		database.AfterCommit(ctx, func(ctx context.Context) {
			s.immediate.RelayImmediately(ctx, event.ID)
		})
	}
	return nil
}

// ClaimNext atomically moves the oldest PENDING event (or a PROCESSING one
// whose lock is older than lockTimeout, i.e. its relay died mid-publish) to
// PROCESSING and returns it. findOneAndUpdate guarantees that concurrent
// relays never claim the same event. It returns nil when nothing is
// claimable.
func (s *Store) ClaimNext(ctx context.Context, lockTimeout time.Duration) (*Event, error) {
	now := time.Now().UTC()
	filter := bson.D{{Key: "$or", Value: bson.A{
		bson.D{{Key: "status", Value: StatusPending}},
		bson.D{
			{Key: "status", Value: StatusProcessing},
			{Key: "locked_at", Value: bson.D{{Key: "$lt", Value: now.Add(-lockTimeout)}}},
		},
	}}}
	return s.claim(ctx, now, filter, options.FindOneAndUpdate().SetSort(bson.D{{Key: "created_at", Value: 1}}))
}

// ClaimIfPending claims event id only if it is still PENDING (the immediate
// send after commit). It returns nil when the event is gone or a relay batch
// already claimed it.
func (s *Store) ClaimIfPending(ctx context.Context, id uuid.UUID) (*Event, error) {
	filter := bson.D{{Key: "_id", Value: id}, {Key: "status", Value: StatusPending}}
	return s.claim(ctx, time.Now().UTC(), filter, options.FindOneAndUpdate())
}

// claim moves the first event matching filter to PROCESSING, locked at now,
// and returns it (nil when nothing matches).
func (s *Store) claim(ctx context.Context, now time.Time, filter bson.D, opts *options.FindOneAndUpdateOptionsBuilder) (*Event, error) {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	update := bson.D{
		{Key: "$set", Value: bson.D{{Key: "status", Value: StatusProcessing}, {Key: "locked_at", Value: now}}},
		{Key: "$inc", Value: bson.D{{Key: "attempts", Value: 1}}},
	}

	var event Event
	err := s.collection.FindOneAndUpdate(ctx, filter, update, opts.SetReturnDocument(options.After)).Decode(&event)
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
// FAILED once attempts are exhausted) and records lastError. Only the claim
// identified by attempts (every claim increments it) may release the event: a
// relay whose lock expired, so the event was reclaimed or already PROCESSED by
// another relay, matches nothing and gets released=false instead of
// overwriting the newer state. MarkProcessed needs no such guard: a confirmed
// publish is true whoever reports it.
func (s *Store) MarkFailedAttempt(ctx context.Context, id uuid.UUID, attempts int, status Status, lastError string) (bool, error) {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	filter := bson.D{
		{Key: "_id", Value: id},
		{Key: "status", Value: StatusProcessing},
		{Key: "attempts", Value: attempts},
	}
	update := bson.D{
		{Key: "$set", Value: bson.D{{Key: "status", Value: status}, {Key: "last_error", Value: lastError}}},
		{Key: "$unset", Value: bson.D{{Key: "locked_at", Value: ""}}},
	}
	result, err := s.collection.UpdateOne(ctx, filter, update)
	if err != nil {
		return false, fmt.Errorf("mark outbox event %s failed attempt: %w", id, err)
	}
	return result.MatchedCount == 1, nil
}
