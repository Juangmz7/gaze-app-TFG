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

// Index names of the two comment list queries of /api/content (task 48). The read
// side hints them, so the query never falls back to another plan.
const (
	// TopLevelCommentsIndex serves GET /api/content/posts/{postId}/comments: top-level
	// comments of a post, most liked, then most replied, then newest.
	TopLevelCommentsIndex = "post_id_1_reply_to_1_like_count_-1_reply_count_-1_created_at_-1_comment_id_-1"
	// RepliesIndex serves GET .../comments/{commentId}/replies: oldest first.
	RepliesIndex = "reply_to_1_created_at_1_comment_id_1"
)

// operationTimeout bounds every Mongo call made by Repository.
const operationTimeout = 5 * time.Second

// commentDocument is the persisted shape of a comment read model entry.
type commentDocument struct {
	CommentID string    `bson:"comment_id"`
	PostID    string    `bson:"post_id"`
	UserID    string    `bson:"user_id"`
	Content   string    `bson:"content"`
	ReplyTo   string    `bson:"reply_to"`
	CreatedAt time.Time `bson:"created_at"`
	UpdatedAt time.Time `bson:"updated_at"`
	// LikeCount and ReplyCount are only written by Insert and adjustCounter, never
	// by Update, and never bump version; see adjustCounter.
	LikeCount  int64 `bson:"like_count"`
	ReplyCount int64 `bson:"reply_count"`
	Version    int64 `bson:"version"`
}

// Repository implements usecase.RecordCommentRepository,
// usecase.UpdateCommentRepository, usecase.DeleteCommentRepository and the comment
// counter ports of the comment like usecases against MongoDB.
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
// creation event, and the two indexes whose key order matches the sort of the
// comment lists exactly, so every page is an index seek with no SORT stage:
// TopLevelCommentsIndex and RepliesIndex.
func (r *Repository) EnsureIndexes(ctx context.Context) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	indexModels := []mongo.IndexModel{
		{
			Keys:    bson.D{{Key: "comment_id", Value: 1}},
			Options: options.Index().SetUnique(true),
		},
		{
			Keys: bson.D{
				{Key: "post_id", Value: 1},
				{Key: "reply_to", Value: 1},
				{Key: "like_count", Value: -1},
				{Key: "reply_count", Value: -1},
				{Key: "created_at", Value: -1},
				{Key: "comment_id", Value: -1},
			},
			Options: options.Index().SetName(TopLevelCommentsIndex),
		},
		{
			Keys: bson.D{
				{Key: "reply_to", Value: 1},
				{Key: "created_at", Value: 1},
				{Key: "comment_id", Value: 1},
			},
			Options: options.Index().SetName(RepliesIndex),
		},
	}

	if _, err := r.collection.Indexes().CreateMany(ctx, indexModels); err != nil {
		return fmt.Errorf("ensure post_comments indexes: %w", err)
	}

	return nil
}

// migrationsCollectionName holds one marker per one-off data migration that ran.
const migrationsCollectionName = "schema_migrations"

// counterBackfillMigration marks that BackfillCounters already ran on this database.
const counterBackfillMigration = "post_comments_counters_v1"

// BackfillCounters gives like_count and reply_count to the comments projected before
// the counters existed, once per database: a missing counter sorts below every
// number, so the keyset of the comment list would skip it after the first page.
// like_count starts at 0, because no comment like was projected before it existed;
// reply_count is counted from the stored replies. A marker in schema_migrations makes
// every later bootstrap a single lookup, and only documents still missing a counter
// are written, so a run interrupted before the marker is safe to repeat.
func (r *Repository) BackfillCounters(ctx context.Context) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	migrations := r.collection.Database().Collection(migrationsCollectionName)
	marker := bson.D{{Key: "_id", Value: counterBackfillMigration}}
	err := migrations.FindOne(ctx, marker).Err()
	if err == nil {
		return nil
	}
	if !errors.Is(err, mongo.ErrNoDocuments) {
		return fmt.Errorf("find migration %s: %w", counterBackfillMigration, err)
	}

	if err := r.backfillReplyCounts(ctx); err != nil {
		return err
	}
	missingLikeCount := bson.D{{Key: "like_count", Value: bson.D{{Key: "$exists", Value: false}}}}
	if _, err := r.collection.UpdateMany(ctx, missingLikeCount, bson.D{{Key: "$set", Value: bson.D{{Key: "like_count", Value: int64(0)}}}}); err != nil {
		return fmt.Errorf("backfill post_comments like_count: %w", err)
	}

	if _, err := migrations.InsertOne(ctx, append(marker, bson.E{Key: "applied_at", Value: time.Now().UTC()})); err != nil && !mongo.IsDuplicateKeyError(err) {
		return fmt.Errorf("record migration %s: %w", counterBackfillMigration, err)
	}

	return nil
}

// backfillReplyCounts sets reply_count, where it is missing, from the stored replies.
func (r *Repository) backfillReplyCounts(ctx context.Context) error {
	cursor, err := r.collection.Aggregate(ctx, mongo.Pipeline{
		{{Key: "$match", Value: bson.D{{Key: "reply_to", Value: bson.D{{Key: "$ne", Value: ""}}}}}},
		{{Key: "$group", Value: bson.D{{Key: "_id", Value: "$reply_to"}, {Key: "count", Value: bson.D{{Key: "$sum", Value: int64(1)}}}}}},
	})
	if err != nil {
		return fmt.Errorf("count post_comments replies: %w", err)
	}
	var replyCounts []struct {
		ParentID string `bson:"_id"`
		Count    int64  `bson:"count"`
	}
	if err := cursor.All(ctx, &replyCounts); err != nil {
		return fmt.Errorf("read post_comments reply counts: %w", err)
	}

	missingReplyCount := bson.E{Key: "reply_count", Value: bson.D{{Key: "$exists", Value: false}}}
	for _, parent := range replyCounts {
		filter := bson.D{{Key: "comment_id", Value: parent.ParentID}, missingReplyCount}
		if _, err := r.collection.UpdateOne(ctx, filter, bson.D{{Key: "$set", Value: bson.D{{Key: "reply_count", Value: parent.Count}}}}); err != nil {
			return fmt.Errorf("backfill reply_count of comment %s: %w", parent.ParentID, err)
		}
	}
	if _, err := r.collection.UpdateMany(ctx, bson.D{missingReplyCount}, bson.D{{Key: "$set", Value: bson.D{{Key: "reply_count", Value: int64(0)}}}}); err != nil {
		return fmt.Errorf("backfill post_comments reply_count: %w", err)
	}

	return nil
}

// Insert writes input as a new comment read model document at version 1 and
// reports whether it really created one. It runs inside the handler's transaction,
// where a duplicate-key error aborts the whole transaction, so an existing document
// is looked up first and reported as (false, nil). A duplicate-key error can then
// only come from a concurrent writer: it is returned as a retryable error, and the
// redelivery finds the document.
func (r *Repository) Insert(ctx context.Context, input usecase.RecordCommentInput) (bool, error) {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	filter := bson.D{{Key: "comment_id", Value: input.CommentID.String()}}
	err := r.collection.FindOne(ctx, filter, options.FindOne().SetProjection(bson.D{{Key: "_id", Value: 1}})).Err()
	if err == nil {
		return false, nil
	}
	if !errors.Is(err, mongo.ErrNoDocuments) {
		return false, fmt.Errorf("find comment %s: %w", input.CommentID, err)
	}

	replyTo := ""
	if input.ReplyTo != uuid.Nil {
		replyTo = input.ReplyTo.String()
	}
	updatedAt := input.UpdatedAt
	if updatedAt.IsZero() {
		updatedAt = input.CreatedAt
	}
	// A reply whose parent was not projected yet was stored without moving any
	// counter (see RecordCommentUsecase), so the parent starts from its stored replies.
	replyCount, err := r.collection.CountDocuments(ctx, bson.D{{Key: "reply_to", Value: input.CommentID.String()}})
	if err != nil {
		return false, fmt.Errorf("count replies of comment %s: %w", input.CommentID, err)
	}

	doc := commentDocument{
		CommentID:  input.CommentID.String(),
		PostID:     input.PostID.String(),
		UserID:     input.UserID.String(),
		Content:    input.Content,
		ReplyTo:    replyTo,
		CreatedAt:  input.CreatedAt,
		UpdatedAt:  updatedAt,
		LikeCount:  0,
		ReplyCount: replyCount,
		Version:    1,
	}
	if _, err := r.collection.InsertOne(ctx, doc); err != nil {
		return false, fmt.Errorf("insert comment %s: %w", input.CommentID, err)
	}

	return true, nil
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

// Delete idempotently removes commentID's comment read model document and reports
// whether a document was really removed and, for a reply, the comment it replied to
// (uuid.Nil for a top-level comment or when nothing was removed). Deleting an
// already-absent document is not an error.
func (r *Repository) Delete(ctx context.Context, commentID uuid.UUID) (bool, uuid.UUID, error) {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	filter := bson.D{{Key: "comment_id", Value: commentID.String()}}
	var removed struct {
		ReplyTo string `bson:"reply_to"`
	}
	err := r.collection.FindOneAndDelete(ctx, filter, options.FindOneAndDelete().SetProjection(bson.D{{Key: "reply_to", Value: 1}})).Decode(&removed)
	if errors.Is(err, mongo.ErrNoDocuments) {
		return false, uuid.Nil, nil
	}
	if err != nil {
		return false, uuid.Nil, fmt.Errorf("delete comment %s: %w", commentID, err)
	}
	if removed.ReplyTo == "" {
		return true, uuid.Nil, nil
	}
	parentID, err := uuid.Parse(removed.ReplyTo)
	if err != nil {
		return true, uuid.Nil, fmt.Errorf("delete comment %s: stored reply_to %q is not a uuid: %w", commentID, removed.ReplyTo, err)
	}

	return true, parentID, nil
}

// AdjustLikeCount adds delta to like_count. See adjustCounter.
func (r *Repository) AdjustLikeCount(ctx context.Context, commentID uuid.UUID, delta int64) (bool, error) {
	return r.adjustCounter(ctx, commentID, "like_count", delta)
}

// AdjustReplyCount adds delta to reply_count. See adjustCounter.
func (r *Repository) AdjustReplyCount(ctx context.Context, commentID uuid.UUID, delta int64) (bool, error) {
	return r.adjustCounter(ctx, commentID, "reply_count", delta)
}

// adjustCounter atomically adds delta to one counter, flooring it at 0, and reports
// whether the comment exists. One aggregation-pipeline update expresses the floor and
// treats a missing counter as 0, like the post counters. It does not touch version:
// Update's optimistic concurrency would otherwise conflict with every like.
func (r *Repository) adjustCounter(ctx context.Context, commentID uuid.UUID, counter string, delta int64) (bool, error) {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	update := mongo.Pipeline{
		{{Key: "$set", Value: bson.D{
			{Key: counter, Value: bson.D{{Key: "$max", Value: bson.A{
				int64(0),
				bson.D{{Key: "$add", Value: bson.A{
					bson.D{{Key: "$ifNull", Value: bson.A{"$" + counter, int64(0)}}},
					delta,
				}}},
			}}}},
		}}},
	}

	result, err := r.collection.UpdateOne(ctx, bson.D{{Key: "comment_id", Value: commentID.String()}}, update)
	if err != nil {
		return false, fmt.Errorf("adjust %s of comment %s: %w", counter, commentID, err)
	}

	return result.MatchedCount > 0, nil
}
