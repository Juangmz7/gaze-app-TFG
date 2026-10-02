// Package mongo implements collab/application/usecase ports against
// MongoDB.
package mongo

import (
	"context"
	"fmt"
	"time"

	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/application/usecase"
)

// collectionName is the MongoDB collection backing the collab read model.
const collectionName = "post_collabs"

// operationTimeout bounds every Mongo call made by Repository.
const operationTimeout = 5 * time.Second

// collabDocument is the persisted shape of a collab read model entry.
type collabDocument struct {
	CollabID    string    `bson:"collab_id"`
	PostID      string    `bson:"post_id"`
	OwnerUserID string    `bson:"owner_user_id"`
	CreatedAt   time.Time `bson:"created_at"`
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

// Upsert idempotently writes input's collab read model document.
func (r *Repository) Upsert(ctx context.Context, input usecase.Input) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	filter := bson.D{{Key: "collab_id", Value: input.CollabID.String()}}
	update := bson.D{{Key: "$set", Value: collabDocument{
		CollabID:    input.CollabID.String(),
		PostID:      input.PostID.String(),
		OwnerUserID: input.OwnerUserID.String(),
		CreatedAt:   input.CreatedAt,
	}}}

	if _, err := r.collection.UpdateOne(ctx, filter, update, options.UpdateOne().SetUpsert(true)); err != nil {
		return fmt.Errorf("upsert collab %s: %w", input.CollabID, err)
	}

	return nil
}
