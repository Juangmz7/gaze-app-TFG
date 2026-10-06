// Package mongo implements post/application/usecase ports against MongoDB.
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

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/post/application/usecase"
)

// collectionName is the MongoDB collection backing the post read model.
const collectionName = "posts"

// operationTimeout bounds every Mongo call made by Repository.
const operationTimeout = 5 * time.Second

// ErrVersionConflict is returned by Update when the stored document's
// version does not match UpdateInput.ExpectedVersion, meaning the document
// was modified by another writer since the version was read.
var ErrVersionConflict = errors.New("post version conflict")

// postDocument is the persisted shape of a post read model entry.
type postDocument struct {
	PostID      string    `bson:"post_id"`
	UserID      string    `bson:"user_id"`
	CollabID    string    `bson:"collab_id"`
	PostType    string    `bson:"post_type"`
	Description string    `bson:"description"`
	Tags        []string  `bson:"tags"`
	CreatedAt   time.Time `bson:"created_at"`
	UpdatedAt   time.Time `bson:"updated_at"`
	Version     int64     `bson:"version"`
}

// UpdateInput is the data required to apply an optimistic-concurrency
// update to an existing post read model document. No use case calls Update
// yet; it exists so the repository/mongo layer exposes a version-based
// update capability uniformly across every read-model repository.
type UpdateInput struct {
	PostID          uuid.UUID
	ExpectedVersion int64
	PostType        string
	Description     string
	Tags            []string
	UpdatedAt       time.Time
}

// Repository implements usecase.Repository against MongoDB.
type Repository struct {
	collection *mongo.Collection
}

// NewRepository creates a Repository backed by db's posts collection.
func NewRepository(db *mongo.Database) *Repository {
	return &Repository{collection: db.Collection(collectionName)}
}

// EnsureIndexes creates the unique index on post_id required to make Insert
// idempotent under concurrent or retried deliveries of the same creation
// event.
func (r *Repository) EnsureIndexes(ctx context.Context) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	indexModel := mongo.IndexModel{
		Keys:    bson.D{{Key: "post_id", Value: 1}},
		Options: options.Index().SetUnique(true),
	}

	if _, err := r.collection.Indexes().CreateOne(ctx, indexModel); err != nil {
		return fmt.Errorf("ensure posts indexes: %w", err)
	}

	return nil
}

// Insert writes input as a new post read model document at version 1. A
// duplicate delivery of the same post-creation event is already filtered
// out upstream by the idempotency repository before the use case runs, but
// a crash between MarkProcessed and this write makes a retry of the same
// creation event theoretically possible; when that retry hits the unique
// index on post_id, Insert treats the duplicate-key error as an
// already-applied insert and returns nil.
func (r *Repository) Insert(ctx context.Context, input usecase.Input) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	collabID := ""
	if input.CollabID != uuid.Nil {
		collabID = input.CollabID.String()
	}

	doc := postDocument{
		PostID:      input.PostID.String(),
		UserID:      input.UserID.String(),
		CollabID:    collabID,
		PostType:    input.PostType,
		Description: input.Description,
		Tags:        input.Tags,
		CreatedAt:   input.CreatedAt,
		UpdatedAt:   input.UpdatedAt,
		Version:     1,
	}

	if _, err := r.collection.InsertOne(ctx, doc); err != nil {
		if mongo.IsDuplicateKeyError(err) {
			return nil
		}
		return fmt.Errorf("insert post %s: %w", input.PostID, err)
	}

	return nil
}

// Update applies an optimistic-concurrency update to the post read model
// document identified by input.PostID. It only succeeds when the stored
// document's version equals input.ExpectedVersion; on success the changed
// fields are set and the version is incremented by one. If no document
// matched (either the post does not exist or its version has already moved
// on), Update returns ErrVersionConflict.
func (r *Repository) Update(ctx context.Context, input UpdateInput) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	filter := bson.D{
		{Key: "post_id", Value: input.PostID.String()},
		{Key: "version", Value: input.ExpectedVersion},
	}
	update := bson.D{
		{Key: "$set", Value: bson.D{
			{Key: "post_type", Value: input.PostType},
			{Key: "description", Value: input.Description},
			{Key: "tags", Value: input.Tags},
			{Key: "updated_at", Value: input.UpdatedAt},
		}},
		{Key: "$inc", Value: bson.D{{Key: "version", Value: int64(1)}}},
	}

	result, err := r.collection.UpdateOne(ctx, filter, update)
	if err != nil {
		return fmt.Errorf("update post %s: %w", input.PostID, err)
	}
	if result.ModifiedCount == 0 {
		return fmt.Errorf("update post %s: %w", input.PostID, ErrVersionConflict)
	}

	return nil
}
