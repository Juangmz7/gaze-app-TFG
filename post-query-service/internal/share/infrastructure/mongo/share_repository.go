// Package mongo implements share/application/usecase ports against MongoDB.
package mongo

import (
	"context"
	"fmt"
	"time"

	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/share/application/usecase"
)

// collectionName is the MongoDB collection backing the share read model.
const collectionName = "post_shares"

// operationTimeout bounds every Mongo call made by Repository.
const operationTimeout = 5 * time.Second

// shareDocument is the persisted shape of a share read model entry.
type shareDocument struct {
	ShareID   string    `bson:"share_id"`
	PostID    string    `bson:"post_id"`
	UserID    string    `bson:"user_id"`
	CreatedAt time.Time `bson:"created_at"`
}

// Repository implements usecase.Repository against MongoDB.
type Repository struct {
	collection *mongo.Collection
}

// NewRepository creates a Repository backed by db's post_shares collection.
func NewRepository(db *mongo.Database) *Repository {
	return &Repository{collection: db.Collection(collectionName)}
}

// Upsert idempotently writes input's share read model document.
func (r *Repository) Upsert(ctx context.Context, input usecase.Input) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	filter := bson.D{{Key: "share_id", Value: input.ShareID.String()}}
	update := bson.D{{Key: "$set", Value: shareDocument{
		ShareID:   input.ShareID.String(),
		PostID:    input.PostID.String(),
		UserID:    input.UserID.String(),
		CreatedAt: input.CreatedAt,
	}}}

	if _, err := r.collection.UpdateOne(ctx, filter, update, options.UpdateOne().SetUpsert(true)); err != nil {
		return fmt.Errorf("upsert share %s: %w", input.ShareID, err)
	}

	return nil
}
