package mongo

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"
	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

// feedUnitCollectionName is the MongoDB collection holding one document per
// (user_id, post_id).
const feedUnitCollectionName = "feed_units"

// feedUnitTTLIndexName is the name MongoDB gives the TTL index on served_at.
const feedUnitTTLIndexName = "served_at_1"

// feedUnitCleanupTimeout bounds the deletes that span many units: a popular post or
// an active creator can appear in many feeds.
const feedUnitCleanupTimeout = 30 * time.Second

// mongoIndexOptionsConflictCode is the error createIndexes returns when an index
// already exists with other options, here a changed retention.
const mongoIndexOptionsConflictCode = 85

// duplicateKeyCode is the write error code of a unique index violation.
const duplicateKeyCode = 11000

// FeedUnitRepository reads and writes feed_units.
type FeedUnitRepository struct {
	collection *mongo.Collection
}

// NewFeedUnitRepository creates a FeedUnitRepository backed by db.
func NewFeedUnitRepository(db *mongo.Database) *FeedUnitRepository {
	return &FeedUnitRepository{collection: db.Collection(feedUnitCollectionName)}
}

// EnsureIndexes creates the feed_units indexes:
//   - unique (user_id, post_id): overlapping builds or a retry can never duplicate a unit.
//   - unique (user_id, position): cursor reads, in order.
//   - (user_id, served_at, position): the lowest unserved position, without walking
//     every unit the user was already served.
//   - post_id and creator.user_id: clean-up.
//   - TTL on served_at: units never served have a null served_at, which TTL ignores.
func (r *FeedUnitRepository) EnsureIndexes(ctx context.Context, retention time.Duration) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	indexes := []mongo.IndexModel{
		{
			Keys:    bson.D{{Key: "user_id", Value: 1}, {Key: "post_id", Value: 1}},
			Options: options.Index().SetUnique(true),
		},
		{
			Keys:    bson.D{{Key: "user_id", Value: 1}, {Key: "position", Value: 1}},
			Options: options.Index().SetUnique(true),
		},
		{
			Keys: bson.D{{Key: "user_id", Value: 1}, {Key: "served_at", Value: 1}, {Key: "position", Value: 1}},
		},
		{Keys: bson.D{{Key: "post_id", Value: 1}}},
		{Keys: bson.D{{Key: "creator.user_id", Value: 1}}},
	}
	if _, err := r.collection.Indexes().CreateMany(ctx, indexes); err != nil {
		return fmt.Errorf("ensure %s indexes: %w", feedUnitCollectionName, err)
	}

	return r.ensureTTLIndex(ctx, retention)
}

// ensureTTLIndex creates the TTL index, or changes its expiry in place when the
// configured retention changed: createIndexes refuses to do that, and a new retention
// must not stop the service from starting.
func (r *FeedUnitRepository) ensureTTLIndex(ctx context.Context, retention time.Duration) error {
	seconds := int32(retention / time.Second)
	if seconds <= 0 {
		return fmt.Errorf("ensure %s ttl index: retention %s is not positive", feedUnitCollectionName, retention)
	}

	ttlIndex := mongo.IndexModel{
		Keys:    bson.D{{Key: "served_at", Value: 1}},
		Options: options.Index().SetExpireAfterSeconds(seconds),
	}
	_, err := r.collection.Indexes().CreateOne(ctx, ttlIndex)
	if err == nil {
		return nil
	}
	var commandErr mongo.CommandError
	if !errors.As(err, &commandErr) || commandErr.Code != mongoIndexOptionsConflictCode {
		return fmt.Errorf("ensure %s ttl index: %w", feedUnitCollectionName, err)
	}

	collMod := bson.D{
		{Key: "collMod", Value: feedUnitCollectionName},
		{Key: "index", Value: bson.D{
			{Key: "name", Value: feedUnitTTLIndexName},
			{Key: "expireAfterSeconds", Value: seconds},
		}},
	}
	if err := r.collection.Database().RunCommand(ctx, collMod).Err(); err != nil {
		return fmt.Errorf("change %s ttl index: %w", feedUnitCollectionName, err)
	}

	return nil
}

// InsertUnits stores units with an unordered InsertMany and reports how many were
// really new. It runs outside a transaction on purpose: a duplicate-key error would
// abort the whole transaction, while here it only means that unit was already applied
// and the rest of the batch still goes in. Any other write error fails the call.
func (r *FeedUnitRepository) InsertUnits(ctx context.Context, units []usecase.FeedUnit) (int, error) {
	if len(units) == 0 {
		return 0, nil
	}

	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	documents := make([]usecase.FeedUnitDocument, 0, len(units))
	for _, unit := range units {
		documents = append(documents, usecase.FeedUnitToDocument(unit))
	}

	result, err := r.collection.InsertMany(ctx, documents, options.InsertMany().SetOrdered(false))
	if err == nil {
		return len(result.InsertedIDs), nil
	}

	var bulkErr mongo.BulkWriteException
	if !errors.As(err, &bulkErr) || bulkErr.WriteConcernError != nil || len(bulkErr.WriteErrors) == 0 {
		return 0, fmt.Errorf("insert feed units: %w", err)
	}
	for _, writeErr := range bulkErr.WriteErrors {
		if writeErr.Code != duplicateKeyCode {
			return 0, fmt.Errorf("insert feed units: %w", err)
		}
	}

	return len(documents) - len(bulkErr.WriteErrors), nil
}

// FindPosition returns the position of the user's unit for postID, and whether there
// is one.
func (r *FeedUnitRepository) FindPosition(ctx context.Context, userID, postID string) (int64, bool, error) {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	filter := bson.D{{Key: "user_id", Value: userID}, {Key: "post_id", Value: postID}}
	findOptions := options.FindOne().SetProjection(bson.D{{Key: "position", Value: 1}})

	var document struct {
		Position int64 `bson:"position"`
	}
	err := r.collection.FindOne(ctx, filter, findOptions).Decode(&document)
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			return 0, false, nil
		}
		return 0, false, fmt.Errorf("find feed position of post %s for user %s: %w", postID, userID, err)
	}

	return document.Position, true, nil
}

// FindAfterPosition reads the next units of a user in position order, projecting only
// what a claim needs. See feedUnitAfterFilter.
func (r *FeedUnitRepository) FindAfterPosition(ctx context.Context, userID string, afterPosition *int64, unservedOnly bool, limit int) ([]usecase.FeedUnitRef, error) {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	findOptions := options.Find().
		SetSort(bson.D{{Key: "position", Value: 1}}).
		SetLimit(int64(limit)).
		SetProjection(bson.D{
			{Key: "post_id", Value: 1},
			{Key: "position", Value: 1},
			{Key: "post_created_at", Value: 1},
		})

	cursor, err := r.collection.Find(ctx, feedUnitAfterFilter(userID, afterPosition, unservedOnly), findOptions)
	if err != nil {
		return nil, fmt.Errorf("find feed units for user %s: %w", userID, err)
	}

	var documents []usecase.FeedUnitDocument
	if err := cursor.All(ctx, &documents); err != nil {
		return nil, fmt.Errorf("read feed units for user %s: %w", userID, err)
	}

	refs := make([]usecase.FeedUnitRef, 0, len(documents))
	for _, document := range documents {
		refs = append(refs, usecase.FeedUnitRefFromDocument(document))
	}

	return refs, nil
}

// CountUnserved counts the user's units never served, stopping at limit: a caller only
// needs to know whether the maximum is reached.
func (r *FeedUnitRepository) CountUnserved(ctx context.Context, userID string, limit int64) (int64, error) {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	count, err := r.collection.CountDocuments(ctx, feedUnitAfterFilter(userID, nil, true), options.Count().SetLimit(limit))
	if err != nil {
		return 0, fmt.Errorf("count unserved feed units for user %s: %w", userID, err)
	}

	return count, nil
}

// FindRecentlyServedPostIDs returns the post ids of the user's most recently served
// units, through the (user_id, served_at, position) index read backwards.
func (r *FeedUnitRepository) FindRecentlyServedPostIDs(ctx context.Context, userID string, limit int) ([]string, error) {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	filter := bson.D{{Key: "user_id", Value: userID}, {Key: "served_at", Value: bson.D{{Key: "$ne", Value: nil}}}}
	findOptions := options.Find().
		SetSort(bson.D{{Key: "served_at", Value: -1}, {Key: "position", Value: -1}}).
		SetLimit(int64(limit)).
		SetProjection(bson.D{{Key: "post_id", Value: 1}})

	cursor, err := r.collection.Find(ctx, filter, findOptions)
	if err != nil {
		return nil, fmt.Errorf("find served feed units for user %s: %w", userID, err)
	}

	var documents []struct {
		PostID string `bson:"post_id"`
	}
	if err := cursor.All(ctx, &documents); err != nil {
		return nil, fmt.Errorf("read served feed units for user %s: %w", userID, err)
	}

	postIDs := make([]string, 0, len(documents))
	for _, document := range documents {
		postIDs = append(postIDs, document.PostID)
	}

	return postIDs, nil
}

// HasAfterPosition reports whether any stored unit matches the same read.
func (r *FeedUnitRepository) HasAfterPosition(ctx context.Context, userID string, afterPosition *int64, unservedOnly bool) (bool, error) {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	findOptions := options.FindOne().SetProjection(bson.D{{Key: "_id", Value: 1}})
	err := r.collection.FindOne(ctx, feedUnitAfterFilter(userID, afterPosition, unservedOnly), findOptions).Err()
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			return false, nil
		}
		return false, fmt.Errorf("check remaining feed units for user %s: %w", userID, err)
	}

	return true, nil
}

// MarkServed sets served_at on the named units that do not have it yet, so retention
// counts from the first time a unit was served, however often a cursor repeats it.
func (r *FeedUnitRepository) MarkServed(ctx context.Context, userID string, postIDs []string, servedAt time.Time) error {
	if len(postIDs) == 0 {
		return nil
	}

	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	filter := bson.D{
		{Key: "user_id", Value: userID},
		{Key: "post_id", Value: bson.D{{Key: "$in", Value: postIDs}}},
		{Key: "served_at", Value: nil},
	}
	update := bson.D{{Key: "$set", Value: bson.D{{Key: "served_at", Value: servedAt}}}}
	if _, err := r.collection.UpdateMany(ctx, filter, update); err != nil {
		return fmt.Errorf("mark feed units served for user %s: %w", userID, err)
	}

	return nil
}

// DeleteByPost idempotently removes every unit of a deleted post, from all feeds.
func (r *FeedUnitRepository) DeleteByPost(ctx context.Context, postID uuid.UUID) error {
	ctx, cancel := context.WithTimeout(ctx, feedUnitCleanupTimeout)
	defer cancel()

	if _, err := r.collection.DeleteMany(ctx, bson.D{{Key: "post_id", Value: postID.String()}}); err != nil {
		return fmt.Errorf("delete feed units of post %s: %w", postID, err)
	}

	return nil
}

// DeleteByUserOrCreator idempotently removes a deleted user's own feed and the units
// of their posts in everyone else's.
func (r *FeedUnitRepository) DeleteByUserOrCreator(ctx context.Context, userID uuid.UUID) error {
	ctx, cancel := context.WithTimeout(ctx, feedUnitCleanupTimeout)
	defer cancel()

	filter := bson.D{{Key: "$or", Value: bson.A{
		bson.D{{Key: "user_id", Value: userID.String()}},
		bson.D{{Key: "creator.user_id", Value: userID.String()}},
	}}}
	if _, err := r.collection.DeleteMany(ctx, filter); err != nil {
		return fmt.Errorf("delete feed units of user %s: %w", userID, err)
	}

	return nil
}

// feedUnitAfterFilter selects a user's units strictly after afterPosition (from the
// start when it is nil), optionally only those never served. served_at: null matches
// the explicit null every unit is stored with.
func feedUnitAfterFilter(userID string, afterPosition *int64, unservedOnly bool) bson.D {
	filter := bson.D{{Key: "user_id", Value: userID}}
	if unservedOnly {
		filter = append(filter, bson.E{Key: "served_at", Value: nil})
	}
	if afterPosition != nil {
		filter = append(filter, bson.E{Key: "position", Value: bson.D{{Key: "$gt", Value: *afterPosition}}})
	}

	return filter
}
