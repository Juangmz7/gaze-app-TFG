// Package mongo implements like/application/usecase ports against MongoDB.
package mongo

import (
	"context"
	"fmt"
	"time"

	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/like/application/usecase/recordlike"
)

// collectionName is the MongoDB collection backing the like read model.
const collectionName = "post_likes"

// operationTimeout bounds every Mongo call made by Repository.
const operationTimeout = 5 * time.Second

// likeDocument is the persisted shape of a like read model entry.
type likeDocument struct {
	LikeID    string    `bson:"like_id"`
	PostID    string    `bson:"post_id"`
	UserID    string    `bson:"user_id"`
	CreatedAt time.Time `bson:"created_at"`
}

// Repository implements recordlike.Repository against MongoDB.
type Repository struct {
	collection *mongo.Collection
}

// NewRepository creates a Repository backed by db's post_likes collection.
func NewRepository(db *mongo.Database) *Repository {
	return &Repository{collection: db.Collection(collectionName)}
}

// Upsert idempotently writes input's like read model document.
func (r *Repository) Upsert(ctx context.Context, input recordlike.Input) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	filter := bson.D{{Key: "like_id", Value: input.LikeID.String()}}
	update := bson.D{{Key: "$set", Value: likeDocument{
		LikeID:    input.LikeID.String(),
		PostID:    input.PostID.String(),
		UserID:    input.UserID.String(),
		CreatedAt: input.CreatedAt,
	}}}

	if _, err := r.collection.UpdateOne(ctx, filter, update, options.UpdateOne().SetUpsert(true)); err != nil {
		return fmt.Errorf("upsert like %s: %w", input.LikeID, err)
	}

	return nil
}
