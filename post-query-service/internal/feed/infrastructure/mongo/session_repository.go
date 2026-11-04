// Package mongo implements feed/application/usecase ports against MongoDB.
package mongo

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"
)

// sessionCollectionName is the MongoDB collection holding one presence document per
// user.
const sessionCollectionName = "user_feed_sessions"

// operationTimeout bounds every single-document or single-page Mongo call of this
// package.
const operationTimeout = 5 * time.Second

// sessionUpsertAttempts bounds the retries of an upsert that loses the race to create
// a user's document. The loser's retry finds the document and updates it.
const sessionUpsertAttempts = 3

// SessionRepository reads and writes user_feed_sessions. Every document has all of
// user_id, last_heartbeat_date, disconnected_at, last_prefetch_at, prefetch_due,
// next_position, prefetch_lease_owner, prefetch_lease_token and
// prefetch_lease_expires_at; the prefetch and lease fields stay at their initial
// values until task 44. next_position is the next free feed position; positions start
// at 0, which post-command-service accepts as a feedPosition.
//
// This collection is session state, not an event projection, so it is the documented
// exception to the conventions rule against SetUpsert(true): there is no creation
// event to Insert from, any of three writes can be a user's first, and the unique
// index on user_id is what makes the upserts race-free.
type SessionRepository struct {
	collection *mongo.Collection
}

// NewSessionRepository creates a SessionRepository backed by db.
func NewSessionRepository(db *mongo.Database) *SessionRepository {
	return &SessionRepository{collection: db.Collection(sessionCollectionName)}
}

// EnsureIndexes creates the unique index on user_id and the (prefetch_due,
// last_heartbeat_date) index for the sweeper of task 44.
func (r *SessionRepository) EnsureIndexes(ctx context.Context) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	indexes := []mongo.IndexModel{
		{
			Keys:    bson.D{{Key: "user_id", Value: 1}},
			Options: options.Index().SetUnique(true),
		},
		{
			Keys: bson.D{{Key: "prefetch_due", Value: 1}, {Key: "last_heartbeat_date", Value: 1}},
		},
	}
	if _, err := r.collection.Indexes().CreateMany(ctx, indexes); err != nil {
		return fmt.Errorf("ensure %s indexes: %w", sessionCollectionName, err)
	}

	return nil
}

// RecordHeartbeat upserts the user's document, marking them present and due a
// prefetch, and clears any previous disconnect.
func (r *SessionRepository) RecordHeartbeat(ctx context.Context, userID uuid.UUID, now time.Time) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	update := bson.D{
		{Key: "$set", Value: bson.D{
			{Key: "last_heartbeat_date", Value: now},
			{Key: "prefetch_due", Value: true},
			{Key: "disconnected_at", Value: nil},
		}},
		{Key: "$setOnInsert", Value: sessionDefaults("next_position", "last_prefetch_at", "prefetch_lease_owner", "prefetch_lease_token", "prefetch_lease_expires_at")},
	}
	if err := r.upsert(ctx, userID, update); err != nil {
		return fmt.Errorf("record heartbeat for user %s: %w", userID, err)
	}

	return nil
}

// RecordDisconnect writes the end of the session to the same document. It upserts
// too: a disconnect can be the first thing the service hears from a user.
func (r *SessionRepository) RecordDisconnect(ctx context.Context, userID uuid.UUID, now time.Time) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	update := bson.D{
		{Key: "$set", Value: bson.D{
			{Key: "disconnected_at", Value: now},
			{Key: "last_heartbeat_date", Value: now},
		}},
		{Key: "$setOnInsert", Value: sessionDefaults("next_position", "last_prefetch_at", "prefetch_due", "prefetch_lease_owner", "prefetch_lease_token", "prefetch_lease_expires_at")},
	}
	if err := r.upsert(ctx, userID, update); err != nil {
		return fmt.Errorf("record disconnect for user %s: %w", userID, err)
	}

	return nil
}

// ReservePositions atomically reserves count consecutive positions for the user and
// returns the first. It is one $inc over the whole batch, never max(position)+1, so
// overlapping builders always get disjoint ranges. Gaps are allowed.
func (r *SessionRepository) ReservePositions(ctx context.Context, userID uuid.UUID, count int64) (int64, error) {
	if count <= 0 {
		return 0, fmt.Errorf("reserve positions for user %s: count %d is not positive", userID, count)
	}

	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	update := bson.D{
		{Key: "$inc", Value: bson.D{{Key: "next_position", Value: count}}},
		{Key: "$setOnInsert", Value: sessionDefaults("last_heartbeat_date", "disconnected_at", "last_prefetch_at", "prefetch_due", "prefetch_lease_owner", "prefetch_lease_token", "prefetch_lease_expires_at")},
	}
	findOptions := options.FindOneAndUpdate().
		SetUpsert(true).
		SetReturnDocument(options.After).
		SetProjection(bson.D{{Key: "next_position", Value: 1}})

	var document struct {
		NextPosition int64 `bson:"next_position"`
	}
	var err error
	for attempt := 0; attempt < sessionUpsertAttempts; attempt++ {
		err = r.collection.FindOneAndUpdate(ctx, sessionFilter(userID), update, findOptions).Decode(&document)
		if err == nil {
			return document.NextPosition - count, nil
		}
		if !mongo.IsDuplicateKeyError(err) {
			break
		}
	}

	return 0, fmt.Errorf("reserve positions for user %s: %w", userID, err)
}

// DeleteByUser idempotently removes the user's presence document.
func (r *SessionRepository) DeleteByUser(ctx context.Context, userID uuid.UUID) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	if _, err := r.collection.DeleteOne(ctx, sessionFilter(userID)); err != nil {
		return fmt.Errorf("delete feed session of user %s: %w", userID, err)
	}

	return nil
}

// upsert applies update to the user's document, retrying when it loses the race to
// create it.
func (r *SessionRepository) upsert(ctx context.Context, userID uuid.UUID, update bson.D) error {
	var err error
	for attempt := 0; attempt < sessionUpsertAttempts; attempt++ {
		_, err = r.collection.UpdateOne(ctx, sessionFilter(userID), update, options.UpdateOne().SetUpsert(true))
		if err == nil || !mongo.IsDuplicateKeyError(err) {
			return err
		}
	}

	return err
}

func sessionFilter(userID uuid.UUID) bson.D {
	return bson.D{{Key: "user_id", Value: userID.String()}}
}

// sessionDefaults returns the initial value of the named fields, for $setOnInsert:
// every field an update does not set itself, so the document always has all of them.
func sessionDefaults(fields ...string) bson.D {
	defaults := make(bson.D, 0, len(fields))
	for _, field := range fields {
		var value any
		switch field {
		case "next_position":
			value = int64(0)
		case "prefetch_due":
			value = false
		}
		defaults = append(defaults, bson.E{Key: field, Value: value})
	}

	return defaults
}
