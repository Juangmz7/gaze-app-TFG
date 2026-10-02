// Package mongo implements user/application/usecase ports against MongoDB.
package mongo

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/user/application/usecase"
)

// collectionName is the MongoDB collection backing the user read model.
const collectionName = "users"

// operationTimeout bounds every Mongo call made by Repository.
const operationTimeout = 5 * time.Second

// userDocument is the persisted shape of a user read model entry.
type userDocument struct {
	UserID    string    `bson:"user_id"`
	Username  string    `bson:"username"`
	CreatedAt time.Time `bson:"created_at"`
}

// Repository implements usecase.RegisterUserRepository and
// usecase.DeleteUserRepository against MongoDB.
type Repository struct {
	collection *mongo.Collection
}

// NewRepository creates a Repository backed by db's users collection.
func NewRepository(db *mongo.Database) *Repository {
	return &Repository{collection: db.Collection(collectionName)}
}

// Upsert idempotently writes input's user read model document.
func (r *Repository) Upsert(ctx context.Context, input usecase.RegisterUserInput) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	filter := bson.D{{Key: "user_id", Value: input.UserID.String()}}
	update := bson.D{{Key: "$set", Value: userDocument{
		UserID:    input.UserID.String(),
		Username:  input.Username,
		CreatedAt: input.CreatedAt,
	}}}

	if _, err := r.collection.UpdateOne(ctx, filter, update, options.UpdateOne().SetUpsert(true)); err != nil {
		return fmt.Errorf("upsert user %s: %w", input.UserID, err)
	}

	return nil
}

// Delete idempotently removes userID's user read model document. Deleting an
// already-absent document is not an error.
func (r *Repository) Delete(ctx context.Context, userID uuid.UUID) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	filter := bson.D{{Key: "user_id", Value: userID.String()}}
	if _, err := r.collection.DeleteOne(ctx, filter); err != nil {
		return fmt.Errorf("delete user %s: %w", userID, err)
	}

	return nil
}
