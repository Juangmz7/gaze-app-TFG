// Package mongo implements share/application/usecase ports against MongoDB.
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

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/share/application/usecase"
)

// collectionName is the MongoDB collection backing the share read model.
const collectionName = "post_shares"

// operationTimeout bounds every Mongo call made by Repository.
const operationTimeout = 5 * time.Second

// ErrVersionConflict is returned by Update when the stored document's
// version does not match UpdateInput.ExpectedVersion, meaning the document
// was modified by another writer since the version was read.
var ErrVersionConflict = errors.New("share version conflict")

// shareDocument is the persisted shape of a share read model entry.
type shareDocument struct {
	ShareID   string    `bson:"share_id"`
	PostID    string    `bson:"post_id"`
	UserID    string    `bson:"user_id"`
	CreatedAt time.Time `bson:"created_at"`
	Version   int64     `bson:"version"`
}

// UpdateInput is the data required to apply an optimistic-concurrency
// update to an existing share read model document. No use case calls Update
// yet; it exists so the repository/mongo layer exposes a version-based
// update capability uniformly across every read-model repository.
type UpdateInput struct {
	ShareID         uuid.UUID
	ExpectedVersion int64
	PostID          uuid.UUID
	UserID          uuid.UUID
}

// Repository implements usecase.Repository against MongoDB.
type Repository struct {
	collection *mongo.Collection
}

// NewRepository creates a Repository backed by db's post_shares collection.
func NewRepository(db *mongo.Database) *Repository {
	return &Repository{collection: db.Collection(collectionName)}
}

// EnsureIndexes creates the unique index on share_id required to make
// Insert idempotent under concurrent or retried deliveries of the same
// creation event.
func (r *Repository) EnsureIndexes(ctx context.Context) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	indexModel := mongo.IndexModel{
		Keys:    bson.D{{Key: "share_id", Value: 1}},
		Options: options.Index().SetUnique(true),
	}

	if _, err := r.collection.Indexes().CreateOne(ctx, indexModel); err != nil {
		return fmt.Errorf("ensure post_shares indexes: %w", err)
	}

	return nil
}

// Insert writes input as a new share read model document at version 1. A
// duplicate delivery of the same share-creation event is already filtered
// out upstream by the idempotency repository before the use case runs, but
// a crash between MarkProcessed and this write makes a retry of the same
// creation event theoretically possible; when that retry hits the unique
// index on share_id, Insert treats the duplicate-key error as an
// already-applied insert and returns nil.
func (r *Repository) Insert(ctx context.Context, input usecase.RecordShareInput) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	doc := shareDocument{
		ShareID:   input.ShareID.String(),
		PostID:    input.PostID.String(),
		UserID:    input.UserID.String(),
		CreatedAt: input.CreatedAt,
		Version:   1,
	}

	if _, err := r.collection.InsertOne(ctx, doc); err != nil {
		if mongo.IsDuplicateKeyError(err) {
			return nil
		}
		return fmt.Errorf("insert share %s: %w", input.ShareID, err)
	}

	return nil
}

// Update applies an optimistic-concurrency update to the share read model
// document identified by input.ShareID. It only succeeds when the stored
// document's version equals input.ExpectedVersion; on success the changed
// fields are set and the version is incremented by one. If no document
// matched (either the share does not exist or its version has already
// moved on), Update returns ErrVersionConflict.
func (r *Repository) Update(ctx context.Context, input UpdateInput) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	filter := bson.D{
		{Key: "share_id", Value: input.ShareID.String()},
		{Key: "version", Value: input.ExpectedVersion},
	}
	update := bson.D{
		{Key: "$set", Value: bson.D{
			{Key: "post_id", Value: input.PostID.String()},
			{Key: "user_id", Value: input.UserID.String()},
		}},
		{Key: "$inc", Value: bson.D{{Key: "version", Value: int64(1)}}},
	}

	result, err := r.collection.UpdateOne(ctx, filter, update)
	if err != nil {
		return fmt.Errorf("update share %s: %w", input.ShareID, err)
	}
	if result.ModifiedCount == 0 {
		return fmt.Errorf("update share %s: %w", input.ShareID, ErrVersionConflict)
	}

	return nil
}

// DeleteByPostAndUser idempotently removes the share read model document
// matching postID and userID. See usecase.DeleteShareInput's doc comment
// for why PostShareDeletedEvent is projected by this filter instead of by
// share_id. Deleting when no document matches is not an error.
func (r *Repository) DeleteByPostAndUser(ctx context.Context, postID, userID uuid.UUID) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	filter := bson.D{
		{Key: "post_id", Value: postID.String()},
		{Key: "user_id", Value: userID.String()},
	}
	if _, err := r.collection.DeleteOne(ctx, filter); err != nil {
		return fmt.Errorf("delete share for post %s user %s: %w", postID, userID, err)
	}

	return nil
}
