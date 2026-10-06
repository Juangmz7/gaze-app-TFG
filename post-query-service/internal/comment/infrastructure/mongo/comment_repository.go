// Package mongo implements comment/application/usecase ports against
// MongoDB.
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
	UpdatedAt time.Time `bson:"updated_at"`
	Version   int64     `bson:"version"`
}

// Repository implements usecase.RecordCommentRepository,
// usecase.UpdateCommentRepository, and usecase.DeleteCommentRepository
// against MongoDB.
type Repository struct {
	collection *mongo.Collection
}

// NewRepository creates a Repository backed by db's post_comments
// collection.
func NewRepository(db *mongo.Database) *Repository {
	return &Repository{collection: db.Collection(collectionName)}
}

// EnsureIndexes creates the unique index on comment_id required to make
// Insert idempotent under concurrent or retried deliveries of the same
// creation event.
func (r *Repository) EnsureIndexes(ctx context.Context) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	indexModel := mongo.IndexModel{
		Keys:    bson.D{{Key: "comment_id", Value: 1}},
		Options: options.Index().SetUnique(true),
	}

	if _, err := r.collection.Indexes().CreateOne(ctx, indexModel); err != nil {
		return fmt.Errorf("ensure post_comments indexes: %w", err)
	}

	return nil
}

// Insert writes input as a new comment read model document at version 1. A
// duplicate delivery of the same comment-creation event is already filtered
// out upstream by the idempotency repository before the use case runs, but
// a crash between MarkProcessed and this write makes a retry of the same
// creation event theoretically possible; when that retry hits the unique
// index on comment_id, Insert treats the duplicate-key error as an
// already-applied insert and returns nil.
func (r *Repository) Insert(ctx context.Context, input usecase.RecordCommentInput) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	doc := commentDocument{
		CommentID: input.CommentID.String(),
		PostID:    input.PostID.String(),
		UserID:    input.UserID.String(),
		Content:   input.Content,
		CreatedAt: input.CreatedAt,
		UpdatedAt: input.CreatedAt,
		Version:   1,
	}

	if _, err := r.collection.InsertOne(ctx, doc); err != nil {
		if mongo.IsDuplicateKeyError(err) {
			return nil
		}
		return fmt.Errorf("insert comment %s: %w", input.CommentID, err)
	}

	return nil
}

// GetVersion returns the current version of the comment read model
// document identified by commentID, and whether it exists at all.
// UpdateCommentUsecase uses it to fill in UpdateCommentInput.ExpectedVersion
// before calling Update, since CommentUpdatedEvent carries no version of
// its own.
func (r *Repository) GetVersion(ctx context.Context, commentID uuid.UUID) (int64, bool, error) {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	var doc struct {
		Version int64 `bson:"version"`
	}
	err := r.collection.FindOne(ctx, bson.D{{Key: "comment_id", Value: commentID.String()}}).Decode(&doc)
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			return 0, false, nil
		}
		return 0, false, fmt.Errorf("get comment %s version: %w", commentID, err)
	}

	return doc.Version, true, nil
}

// FindContentAndUpdatedAt returns the currently stored Content and
// UpdatedAt for the comment read model document identified by commentID,
// and whether it exists at all. UpdateCommentUsecase uses it as a
// version-conflict safety net (see that usecase's doc comment): because
// CommentUpdatedEvent carries no event id for an idempotency check, this
// lets Execute distinguish a harmless redelivery (stored fields already
// equal the incoming event's) from a genuine conflict.
func (r *Repository) FindContentAndUpdatedAt(ctx context.Context, commentID uuid.UUID) (string, time.Time, bool, error) {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	var doc struct {
		Content   string    `bson:"content"`
		UpdatedAt time.Time `bson:"updated_at"`
	}
	err := r.collection.FindOne(ctx, bson.D{{Key: "comment_id", Value: commentID.String()}}).Decode(&doc)
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			return "", time.Time{}, false, nil
		}
		return "", time.Time{}, false, fmt.Errorf("find comment %s content: %w", commentID, err)
	}

	return doc.Content, doc.UpdatedAt, true, nil
}

// Update applies an optimistic-concurrency update to the comment read model
// document identified by input.CommentID. It only succeeds when the stored
// document's version equals input.ExpectedVersion; on success the changed
// fields are set and the version is incremented by one. If no document
// matched (either the comment does not exist or its version has already
// moved on), Update returns usecase.ErrCommentVersionConflict.
func (r *Repository) Update(ctx context.Context, input usecase.UpdateCommentInput) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	filter := bson.D{
		{Key: "comment_id", Value: input.CommentID.String()},
		{Key: "version", Value: input.ExpectedVersion},
	}
	update := bson.D{
		{Key: "$set", Value: bson.D{
			{Key: "post_id", Value: input.PostID.String()},
			{Key: "user_id", Value: input.UserID.String()},
			{Key: "content", Value: input.Content},
			{Key: "updated_at", Value: input.UpdatedAt},
		}},
		{Key: "$inc", Value: bson.D{{Key: "version", Value: int64(1)}}},
	}

	result, err := r.collection.UpdateOne(ctx, filter, update)
	if err != nil {
		return fmt.Errorf("update comment %s: %w", input.CommentID, err)
	}
	if result.ModifiedCount == 0 {
		return fmt.Errorf("update comment %s: %w", input.CommentID, usecase.ErrCommentVersionConflict)
	}

	return nil
}

// Delete idempotently removes commentID's comment read model document.
// Deleting an already-absent document is not an error.
func (r *Repository) Delete(ctx context.Context, commentID uuid.UUID) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	filter := bson.D{{Key: "comment_id", Value: commentID.String()}}
	if _, err := r.collection.DeleteOne(ctx, filter); err != nil {
		return fmt.Errorf("delete comment %s: %w", commentID, err)
	}

	return nil
}
