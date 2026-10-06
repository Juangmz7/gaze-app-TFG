// Package mongo implements follow/application/usecase ports against
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

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/follow/application/usecase"
)

// collectionName is the MongoDB collection backing the follow read model.
const collectionName = "user_follows"

// operationTimeout bounds every Mongo call made by Repository.
const operationTimeout = 5 * time.Second

// followDocument is the persisted shape of a follow read model entry.
type followDocument struct {
	FollowerUserID string    `bson:"follower_user_id"`
	FollowedUserID string    `bson:"followed_user_id"`
	CreatedAt      time.Time `bson:"created_at"`
	Version        int64     `bson:"version"`
}

// Repository implements usecase.CreateFollowRepository and
// usecase.DeleteFollowRepository against MongoDB.
type Repository struct {
	collection *mongo.Collection
}

// NewRepository creates a Repository backed by db's user_follows
// collection.
func NewRepository(db *mongo.Database) *Repository {
	return &Repository{collection: db.Collection(collectionName)}
}

// EnsureIndexes creates the unique compound index on
// (follower_user_id, followed_user_id) required to make Insert idempotent
// under concurrent or retried deliveries of the same creation event.
func (r *Repository) EnsureIndexes(ctx context.Context) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	indexModel := mongo.IndexModel{
		Keys: bson.D{
			{Key: "follower_user_id", Value: 1},
			{Key: "followed_user_id", Value: 1},
		},
		Options: options.Index().SetUnique(true),
	}

	if _, err := r.collection.Indexes().CreateOne(ctx, indexModel); err != nil {
		return fmt.Errorf("ensure user_follows indexes: %w", err)
	}

	return nil
}

// Insert writes input as a new follow read model document at version 1. A
// duplicate delivery of the same follow-created event is already filtered
// out upstream by the idempotency repository before the use case runs, but
// a crash between MarkProcessed and this write makes a retry of the same
// creation event theoretically possible; when that retry hits the unique
// compound index, Insert treats the duplicate-key error as an
// already-applied insert and returns nil.
func (r *Repository) Insert(ctx context.Context, input usecase.CreateFollowInput) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	doc := followDocument{
		FollowerUserID: input.FollowerUserID.String(),
		FollowedUserID: input.FollowedUserID.String(),
		CreatedAt:      input.CreatedAt,
		Version:        1,
	}

	if _, err := r.collection.InsertOne(ctx, doc); err != nil {
		if mongo.IsDuplicateKeyError(err) {
			return nil
		}
		return fmt.Errorf("insert follow %s->%s: %w", input.FollowerUserID, input.FollowedUserID, err)
	}

	return nil
}

// Delete idempotently removes the follow read model document identified by
// followerUserID/followedUserID. Deleting an already-absent document is not
// an error, mirroring user/infrastructure/mongo.Repository.Delete.
func (r *Repository) Delete(ctx context.Context, followerUserID, followedUserID uuid.UUID) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	filter := bson.D{
		{Key: "follower_user_id", Value: followerUserID.String()},
		{Key: "followed_user_id", Value: followedUserID.String()},
	}
	if _, err := r.collection.DeleteOne(ctx, filter); err != nil {
		return fmt.Errorf("delete follow %s->%s: %w", followerUserID, followedUserID, err)
	}

	return nil
}
