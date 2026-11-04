// Package mongo implements share/application/usecase ports against MongoDB.
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

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/share/application/usecase"
)

// collectionName is the MongoDB collection backing the share read model.
const collectionName = "post_shares"

// operationTimeout bounds every Mongo call made by Repository.
const operationTimeout = 5 * time.Second

// legacyShareIDIndex is the name of the unique index on the removed share_id field.
const legacyShareIDIndex = "share_id_1"

// MongoDB server error codes dropIndexes returns when there is nothing to drop.
const (
	mongoNamespaceNotFoundCode = 26
	mongoIndexNotFoundCode     = 27
)

// shareDocument is the persisted shape of a share read model entry. Its identity
// is (post_id, user_id), the same primary key post_shares has in
// post-command-service; the events carry no shareId.
type shareDocument struct {
	PostID    string    `bson:"post_id"`
	UserID    string    `bson:"user_id"`
	CreatedAt time.Time `bson:"created_at"`
	Version   int64     `bson:"version"`
}

// Repository implements usecase.RecordShareRepository and
// usecase.DeleteShareRepository against MongoDB.
type Repository struct {
	collection *mongo.Collection
}

// NewRepository creates a Repository backed by db's post_shares collection.
func NewRepository(db *mongo.Database) *Repository {
	return &Repository{collection: db.Collection(collectionName)}
}

// EnsureIndexes creates the unique index on (post_id, user_id), the share's
// identity, and drops the share_id index earlier versions created: share_id no
// longer exists, so that unique index would treat every new document as a
// duplicate of the first one (all missing values index as null). The
// (user_id, post_id) index serves the feed claim's lookup of a viewer's shares.
func (r *Repository) EnsureIndexes(ctx context.Context) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	if err := r.collection.Indexes().DropOne(ctx, legacyShareIDIndex); err != nil && !isMissingIndexError(err) {
		return fmt.Errorf("drop post_shares legacy index: %w", err)
	}

	indexModels := []mongo.IndexModel{
		{
			Keys:    bson.D{{Key: "post_id", Value: 1}, {Key: "user_id", Value: 1}},
			Options: options.Index().SetUnique(true),
		},
		{Keys: bson.D{{Key: "user_id", Value: 1}, {Key: "post_id", Value: 1}}},
	}

	if _, err := r.collection.Indexes().CreateMany(ctx, indexModels); err != nil {
		return fmt.Errorf("ensure post_shares indexes: %w", err)
	}

	return nil
}

// isMissingIndexError reports whether a dropIndexes failure only means there was
// nothing to drop: no such index, or no such collection yet.
func isMissingIndexError(err error) bool {
	var commandErr mongo.CommandError
	if !errors.As(err, &commandErr) {
		return false
	}
	return commandErr.Code == mongoIndexNotFoundCode || commandErr.Code == mongoNamespaceNotFoundCode
}

// Insert writes input as a new share read model document at version 1 and reports
// whether it really created one. It runs inside the handler's transaction, where
// a duplicate-key error aborts the whole transaction, so an existing document is
// looked up first and reported as (false, nil). A duplicate-key error can then
// only come from a concurrent writer: it is returned as a retryable error, and
// the redelivery finds the document.
func (r *Repository) Insert(ctx context.Context, input usecase.RecordShareInput) (bool, error) {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	filter := bson.D{
		{Key: "post_id", Value: input.PostID.String()},
		{Key: "user_id", Value: input.UserID.String()},
	}
	err := r.collection.FindOne(ctx, filter, options.FindOne().SetProjection(bson.D{{Key: "_id", Value: 1}})).Err()
	if err == nil {
		return false, nil
	}
	if !errors.Is(err, mongo.ErrNoDocuments) {
		return false, fmt.Errorf("find share for post %s user %s: %w", input.PostID, input.UserID, err)
	}

	doc := shareDocument{
		PostID:    input.PostID.String(),
		UserID:    input.UserID.String(),
		CreatedAt: input.CreatedAt,
		Version:   1,
	}
	if _, err := r.collection.InsertOne(ctx, doc); err != nil {
		return false, fmt.Errorf("insert share for post %s user %s: %w", input.PostID, input.UserID, err)
	}

	return true, nil
}

// DeleteByPostAndUser idempotently removes the share read model document matching
// postID and userID and reports whether a document was really removed. Deleting
// when no document matches is not an error.
func (r *Repository) DeleteByPostAndUser(ctx context.Context, postID, userID uuid.UUID) (bool, error) {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	filter := bson.D{
		{Key: "post_id", Value: postID.String()},
		{Key: "user_id", Value: userID.String()},
	}
	result, err := r.collection.DeleteOne(ctx, filter)
	if err != nil {
		return false, fmt.Errorf("delete share for post %s user %s: %w", postID, userID, err)
	}

	return result.DeletedCount > 0, nil
}
