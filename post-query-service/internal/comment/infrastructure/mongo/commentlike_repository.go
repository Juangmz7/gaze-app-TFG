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

// commentLikesCollectionName is the MongoDB collection backing the comment like read
// model.
const commentLikesCollectionName = "comment_likes"

// commentLikeDocument is the persisted shape of a comment like. Its identity is
// (comment_id, user_id), the primary key comment_likes has in post-command-service;
// the events carry no like id.
type commentLikeDocument struct {
	CommentID string    `bson:"comment_id"`
	UserID    string    `bson:"user_id"`
	CreatedAt time.Time `bson:"created_at"`
}

// CommentLikeRepository implements usecase.RecordCommentLikeRepository and
// usecase.DeleteCommentLikeRepository against MongoDB.
type CommentLikeRepository struct {
	collection *mongo.Collection
}

// NewCommentLikeRepository creates a CommentLikeRepository backed by db's
// comment_likes collection.
func NewCommentLikeRepository(db *mongo.Database) *CommentLikeRepository {
	return &CommentLikeRepository{collection: db.Collection(commentLikesCollectionName)}
}

// EnsureIndexes creates the unique index on (comment_id, user_id): it enforces one
// like per pair, serves the delete filter and the comment list's likedByMe lookup
// (comment_id $in the page, user_id the caller).
func (r *CommentLikeRepository) EnsureIndexes(ctx context.Context) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	indexModel := mongo.IndexModel{
		Keys:    bson.D{{Key: "comment_id", Value: 1}, {Key: "user_id", Value: 1}},
		Options: options.Index().SetUnique(true),
	}
	if _, err := r.collection.Indexes().CreateOne(ctx, indexModel); err != nil {
		return fmt.Errorf("ensure comment_likes indexes: %w", err)
	}

	return nil
}

// Insert writes input as a new comment like and reports whether it really created
// one. It runs inside the handler's transaction, where a duplicate-key error aborts
// the whole transaction, so an existing document is looked up first and reported as
// (false, nil). A duplicate-key error can then only come from a concurrent writer: it
// is returned as a retryable error, and the redelivery finds the document.
func (r *CommentLikeRepository) Insert(ctx context.Context, input usecase.RecordCommentLikeInput) (bool, error) {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	filter := commentLikeFilter(input.CommentID, input.UserID)
	err := r.collection.FindOne(ctx, filter, options.FindOne().SetProjection(bson.D{{Key: "_id", Value: 1}})).Err()
	if err == nil {
		return false, nil
	}
	if !errors.Is(err, mongo.ErrNoDocuments) {
		return false, fmt.Errorf("find like of comment %s by user %s: %w", input.CommentID, input.UserID, err)
	}

	doc := commentLikeDocument{
		CommentID: input.CommentID.String(),
		UserID:    input.UserID.String(),
		CreatedAt: input.CreatedAt,
	}
	if _, err := r.collection.InsertOne(ctx, doc); err != nil {
		return false, fmt.Errorf("insert like of comment %s by user %s: %w", input.CommentID, input.UserID, err)
	}

	return true, nil
}

// Delete idempotently removes the like of commentID by userID and reports whether a
// document was really removed. Deleting when no document matches is not an error.
func (r *CommentLikeRepository) Delete(ctx context.Context, commentID, userID uuid.UUID) (bool, error) {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	result, err := r.collection.DeleteOne(ctx, commentLikeFilter(commentID, userID))
	if err != nil {
		return false, fmt.Errorf("delete like of comment %s by user %s: %w", commentID, userID, err)
	}

	return result.DeletedCount > 0, nil
}

func commentLikeFilter(commentID, userID uuid.UUID) bson.D {
	return bson.D{
		{Key: "comment_id", Value: commentID.String()},
		{Key: "user_id", Value: userID.String()},
	}
}
