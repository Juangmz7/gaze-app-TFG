// Package mongo implements collab/application/usecase ports against
// MongoDB.
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

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/application/usecase"
)

// collectionName is the MongoDB collection backing the collab read model.
const collectionName = "post_collabs"

// operationTimeout bounds every Mongo call made by Repository.
const operationTimeout = 5 * time.Second

// ErrVersionConflict is returned by Update when the stored document's
// version does not match UpdateInput.ExpectedVersion, meaning the document
// was modified by another writer since the version was read.
var ErrVersionConflict = errors.New("collab version conflict")

// collabDocument is the persisted shape of a collab read model entry.
type collabDocument struct {
	CollabID    string    `bson:"collab_id"`
	PostID      string    `bson:"post_id"`
	OwnerUserID string    `bson:"owner_user_id"`
	CreatedAt   time.Time `bson:"created_at"`
	Version     int64     `bson:"version"`
}

// UpdateInput is the data required to apply an optimistic-concurrency
// update to an existing collab read model document. collab is one of the
// contexts that will gain a real update consumer (e.g. collab-closed) in a
// follow-up task; this method is wired and tested at the repository level
// now so that future use case does not need repository changes.
type UpdateInput struct {
	CollabID        uuid.UUID
	ExpectedVersion int64
	PostID          uuid.UUID
	OwnerUserID     uuid.UUID
}

// Repository implements usecase.Repository against MongoDB.
type Repository struct {
	collection *mongo.Collection
}

// NewRepository creates a Repository backed by db's post_collabs
// collection.
func NewRepository(db *mongo.Database) *Repository {
	return &Repository{collection: db.Collection(collectionName)}
}

// EnsureIndexes creates the unique index on collab_id required to make
// Insert idempotent under concurrent or retried deliveries of the same
// creation event.
func (r *Repository) EnsureIndexes(ctx context.Context) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	indexModel := mongo.IndexModel{
		Keys:    bson.D{{Key: "collab_id", Value: 1}},
		Options: options.Index().SetUnique(true),
	}

	if _, err := r.collection.Indexes().CreateOne(ctx, indexModel); err != nil {
		return fmt.Errorf("ensure post_collabs indexes: %w", err)
	}

	return nil
}

// Insert writes input as a new collab read model document at version 1. A
// duplicate delivery of the same collab-opened event is already filtered
// out upstream by the idempotency repository before the use case runs, but
// a crash between MarkProcessed and this write makes a retry of the same
// creation event theoretically possible; when that retry hits the unique
// index on collab_id, Insert treats the duplicate-key error as an
// already-applied insert and returns nil.
func (r *Repository) Insert(ctx context.Context, input usecase.Input) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	doc := collabDocument{
		CollabID:    input.CollabID.String(),
		PostID:      input.PostID.String(),
		OwnerUserID: input.OwnerUserID.String(),
		CreatedAt:   input.CreatedAt,
		Version:     1,
	}

	if _, err := r.collection.InsertOne(ctx, doc); err != nil {
		if mongo.IsDuplicateKeyError(err) {
			return nil
		}
		return fmt.Errorf("insert collab %s: %w", input.CollabID, err)
	}

	return nil
}

// Update applies an optimistic-concurrency update to the collab read model
// document identified by input.CollabID. It only succeeds when the stored
// document's version equals input.ExpectedVersion; on success the changed
// fields are set and the version is incremented by one. If no document
// matched (either the collab does not exist or its version has already
// moved on), Update returns ErrVersionConflict.
func (r *Repository) Update(ctx context.Context, input UpdateInput) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	filter := bson.D{
		{Key: "collab_id", Value: input.CollabID.String()},
		{Key: "version", Value: input.ExpectedVersion},
	}
	update := bson.D{
		{Key: "$set", Value: bson.D{
			{Key: "post_id", Value: input.PostID.String()},
			{Key: "owner_user_id", Value: input.OwnerUserID.String()},
		}},
		{Key: "$inc", Value: bson.D{{Key: "version", Value: int64(1)}}},
	}

	result, err := r.collection.UpdateOne(ctx, filter, update)
	if err != nil {
		return fmt.Errorf("update collab %s: %w", input.CollabID, err)
	}
	if result.ModifiedCount == 0 {
		return fmt.Errorf("update collab %s: %w", input.CollabID, ErrVersionConflict)
	}

	return nil
}
