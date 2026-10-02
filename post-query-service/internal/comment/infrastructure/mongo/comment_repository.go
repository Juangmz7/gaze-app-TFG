// Package mongo implements comment/application/usecase ports against
// MongoDB.
package mongo

import (
	"context"
	"fmt"
	"time"

	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/comment/application/usecase"
)

// collectionName is the MongoDB collection backing the comment read model.
const collectionName = "post_comments"

// operationTimeout bounds every Mongo call made by Repository.
const operationTimeout = 5 * time.Second

// commentDocument is the persisted shape of a comment read model entry.
type commentDocument struct {
	CommentID string    `bson:"comment_id"`
	PostID    string    `bson:"post_id"`
	UserID    string    `bson:"user_id"`
	Content   string    `bson:"content"`
	CreatedAt time.Time `bson:"created_at"`
}

// Repository implements usecase.Repository against MongoDB.
type Repository struct {
	collection *mongo.Collection
}

// NewRepository creates a Repository backed by db's post_comments
// collection.
func NewRepository(db *mongo.Database) *Repository {
	return &Repository{collection: db.Collection(collectionName)}
}

// Upsert idempotently writes input's comment read model document.
func (r *Repository) Upsert(ctx context.Context, input usecase.Input) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	filter := bson.D{{Key: "comment_id", Value: input.CommentID.String()}}
	update := bson.D{{Key: "$set", Value: commentDocument{
		CommentID: input.CommentID.String(),
		PostID:    input.PostID.String(),
		UserID:    input.UserID.String(),
		Content:   input.Content,
		CreatedAt: input.CreatedAt,
	}}}

	if _, err := r.collection.UpdateOne(ctx, filter, update, options.UpdateOne().SetUpsert(true)); err != nil {
		return fmt.Errorf("upsert comment %s: %w", input.CommentID, err)
	}

	return nil
}
