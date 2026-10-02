// Package mongo implements post/application/usecase ports against MongoDB.
package mongo

import (
	"context"
	"fmt"
	"time"

	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/post/application/usecase"
)

// collectionName is the MongoDB collection backing the post read model.
const collectionName = "posts"

// operationTimeout bounds every Mongo call made by Repository.
const operationTimeout = 5 * time.Second

// postDocument is the persisted shape of a post read model entry.
type postDocument struct {
	PostID      string    `bson:"post_id"`
	UserID      string    `bson:"user_id"`
	CollabID    string    `bson:"collab_id,omitempty"`
	PostType    string    `bson:"post_type"`
	Description string    `bson:"description"`
	Tags        []string  `bson:"tags"`
	CreatedAt   time.Time `bson:"created_at"`
	UpdatedAt   time.Time `bson:"updated_at"`
}

// Repository implements usecase.Repository against MongoDB.
type Repository struct {
	collection *mongo.Collection
}

// NewRepository creates a Repository backed by db's posts collection.
func NewRepository(db *mongo.Database) *Repository {
	return &Repository{collection: db.Collection(collectionName)}
}

// Upsert idempotently writes input's post read model document.
func (r *Repository) Upsert(ctx context.Context, input usecase.Input) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	filter := bson.D{{Key: "post_id", Value: input.PostID.String()}}
	update := bson.D{{Key: "$set", Value: postDocument{
		PostID:      input.PostID.String(),
		UserID:      input.UserID.String(),
		CollabID:    input.CollabID.String(),
		PostType:    input.PostType,
		Description: input.Description,
		Tags:        input.Tags,
		CreatedAt:   input.CreatedAt,
		UpdatedAt:   input.UpdatedAt,
	}}}

	if _, err := r.collection.UpdateOne(ctx, filter, update, options.UpdateOne().SetUpsert(true)); err != nil {
		return fmt.Errorf("upsert post %s: %w", input.PostID, err)
	}

	return nil
}
