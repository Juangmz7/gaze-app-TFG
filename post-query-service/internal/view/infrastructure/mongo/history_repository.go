// Package mongo implements view/application/usecase ports against MongoDB.
package mongo

import (
	"context"
	"errors"
	"fmt"
	"math"
	"time"

	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/view/application/usecase"
)

// CollectionName is the MongoDB collection of the viewing history: one document per
// (user_id, post_id).
const CollectionName = "view_history"

// UserHistoryIndex is the name of the view_history index that serves GET
// /api/content/me/history (task 50): the caller's history most recently viewed first,
// whose sort it matches exactly, so every keyset page is an index seek.
const UserHistoryIndex = "user_id_1_last_viewed_at_-1_post_id_-1"

// historyTTLIndexName is the name MongoDB gives the TTL index on last_viewed_at.
const historyTTLIndexName = "last_viewed_at_1"

// operationTimeout bounds every Mongo call made by HistoryRepository.
const operationTimeout = 5 * time.Second

// mongoIndexOptionsConflictCode is the error createIndexes returns when an index
// already exists with other options, here a changed retention.
const mongoIndexOptionsConflictCode = 85

// historyDocument is the persisted shape of a viewing history row. It has no version:
// its only change is last_viewed_at = max(stored, new), which gives the same result in
// any delivery order, so there is nothing to gate.
type historyDocument struct {
	UserID       string    `bson:"user_id"`
	PostID       string    `bson:"post_id"`
	LastViewedAt time.Time `bson:"last_viewed_at"`
}

// HistoryRepository implements usecase.RecordPostViewRepository against MongoDB.
type HistoryRepository struct {
	collection *mongo.Collection
}

// NewHistoryRepository creates a HistoryRepository backed by db's view_history
// collection.
func NewHistoryRepository(db *mongo.Database) *HistoryRepository {
	return &HistoryRepository{collection: db.Collection(CollectionName)}
}

// EnsureIndexes creates the view_history indexes:
//   - unique (user_id, post_id): one row per user and post, so the collection is
//     bounded by the posts a user saw, not by the views.
//   - UserHistoryIndex: the history tab's keyset pages.
//   - TTL on last_viewed_at, expiring after retention. A changed retention is applied
//     in place (collMod), since createIndexes refuses to change it and a new value
//     must not stop the service from starting.
func (r *HistoryRepository) EnsureIndexes(ctx context.Context, retention time.Duration) error {
	if retention < time.Second || retention/time.Second > math.MaxInt32 {
		return fmt.Errorf("ensure %s indexes: retention %s must be between 1s and %d seconds", CollectionName, retention, math.MaxInt32)
	}
	seconds := int32(retention / time.Second)

	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	indexes := []mongo.IndexModel{
		{
			Keys:    bson.D{{Key: "user_id", Value: 1}, {Key: "post_id", Value: 1}},
			Options: options.Index().SetUnique(true),
		},
		{
			Keys:    bson.D{{Key: "user_id", Value: 1}, {Key: "last_viewed_at", Value: -1}, {Key: "post_id", Value: -1}},
			Options: options.Index().SetName(UserHistoryIndex),
		},
	}
	if _, err := r.collection.Indexes().CreateMany(ctx, indexes); err != nil {
		return fmt.Errorf("ensure %s indexes: %w", CollectionName, err)
	}

	return r.ensureTTLIndex(ctx, seconds)
}

func (r *HistoryRepository) ensureTTLIndex(ctx context.Context, seconds int32) error {
	ttlIndex := mongo.IndexModel{
		Keys:    bson.D{{Key: "last_viewed_at", Value: 1}},
		Options: options.Index().SetName(historyTTLIndexName).SetExpireAfterSeconds(seconds),
	}
	_, err := r.collection.Indexes().CreateOne(ctx, ttlIndex)
	if err == nil {
		return nil
	}
	var commandErr mongo.CommandError
	if !errors.As(err, &commandErr) || commandErr.Code != mongoIndexOptionsConflictCode {
		return fmt.Errorf("ensure %s ttl index: %w", CollectionName, err)
	}

	collMod := bson.D{
		{Key: "collMod", Value: CollectionName},
		{Key: "index", Value: bson.D{
			{Key: "name", Value: historyTTLIndexName},
			{Key: "expireAfterSeconds", Value: seconds},
		}},
	}
	if err := r.collection.Database().RunCommand(ctx, collMod).Err(); err != nil {
		return fmt.Errorf("change %s ttl index: %w", CollectionName, err)
	}

	return nil
}

// Record keeps one row per (user, post) with last_viewed_at = max(stored,
// input.ViewedAt). It runs inside the handler's transaction, where a duplicate-key
// error aborts the whole transaction, so the existing row is updated first ($max,
// which leaves a newer stored time alone) and a row is inserted only when none
// matched. A duplicate-key error can then only come from a concurrent first view of
// the same post: it is returned as a retryable error, and the redelivery updates the
// row that writer created.
func (r *HistoryRepository) Record(ctx context.Context, input usecase.RecordPostViewInput) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	viewedAt := input.ViewedAt.UTC()
	filter := bson.D{
		{Key: "user_id", Value: input.UserID.String()},
		{Key: "post_id", Value: input.PostID.String()},
	}
	result, err := r.collection.UpdateOne(ctx, filter, bson.D{{Key: "$max", Value: bson.D{{Key: "last_viewed_at", Value: viewedAt}}}})
	if err != nil {
		return fmt.Errorf("update view history of user %s post %s: %w", input.UserID, input.PostID, err)
	}
	if result.MatchedCount > 0 {
		return nil
	}

	doc := historyDocument{UserID: input.UserID.String(), PostID: input.PostID.String(), LastViewedAt: viewedAt}
	if _, err := r.collection.InsertOne(ctx, doc); err != nil {
		return fmt.Errorf("insert view history of user %s post %s: %w", input.UserID, input.PostID, err)
	}

	return nil
}
