// Package mongo implements block/application/usecase ports against
// MongoDB.
package mongo

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/block/application/usecase"
)

// collectionName is the MongoDB collection backing the block read model.
const collectionName = "user_blocks"

// operationTimeout bounds every Mongo call made by Repository.
const operationTimeout = 5 * time.Second

// blockDocument is the persisted shape of a block read model entry.
type blockDocument struct {
	BlockerUserID string    `bson:"blocker_user_id"`
	BlockedUserID string    `bson:"blocked_user_id"`
	CreatedAt     time.Time `bson:"created_at"`
	Version       int64     `bson:"version"`
}

// Repository implements usecase.CreateBlockRepository and
// usecase.DeleteBlockRepository against MongoDB.
type Repository struct {
	collection *mongo.Collection
}

// NewRepository creates a Repository backed by db's user_blocks collection.
func NewRepository(db *mongo.Database) *Repository {
	return &Repository{collection: db.Collection(collectionName)}
}

// EnsureIndexes creates the unique compound index on
// (blocker_user_id, blocked_user_id) required to make Insert idempotent
// under concurrent or retried deliveries of the same creation event.
func (r *Repository) EnsureIndexes(ctx context.Context) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	indexModel := mongo.IndexModel{
		Keys: bson.D{
			{Key: "blocker_user_id", Value: 1},
			{Key: "blocked_user_id", Value: 1},
		},
		Options: options.Index().SetUnique(true),
	}

	if _, err := r.collection.Indexes().CreateOne(ctx, indexModel); err != nil {
		return fmt.Errorf("ensure user_blocks indexes: %w", err)
	}

	return nil
}

// Insert writes input as a new block read model document at version 1. A
// duplicate delivery of the same block-created event is already filtered
// out upstream by the idempotency repository before the use case runs, but
// a crash between MarkProcessed and this write makes a retry of the same
// creation event theoretically possible; when that retry hits the unique
// compound index, Insert treats the duplicate-key error as an
// already-applied insert and returns nil.
func (r *Repository) Insert(ctx context.Context, input usecase.CreateBlockInput) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	doc := blockDocument{
		BlockerUserID: input.BlockerUserID.String(),
		BlockedUserID: input.BlockedUserID.String(),
		CreatedAt:     input.CreatedAt,
		Version:       1,
	}

	if _, err := r.collection.InsertOne(ctx, doc); err != nil {
		if mongo.IsDuplicateKeyError(err) {
			return nil
		}
		return fmt.Errorf("insert block %s->%s: %w", input.BlockerUserID, input.BlockedUserID, err)
	}

	return nil
}

// Delete idempotently removes the block read model document identified by
// blockerUserID/blockedUserID. Deleting an already-absent document is not
// an error, mirroring user/infrastructure/mongo.Repository.Delete.
func (r *Repository) Delete(ctx context.Context, blockerUserID, blockedUserID uuid.UUID) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	filter := bson.D{
		{Key: "blocker_user_id", Value: blockerUserID.String()},
		{Key: "blocked_user_id", Value: blockedUserID.String()},
	}
	if _, err := r.collection.DeleteOne(ctx, filter); err != nil {
		return fmt.Errorf("delete block %s->%s: %w", blockerUserID, blockedUserID, err)
	}

	return nil
}
