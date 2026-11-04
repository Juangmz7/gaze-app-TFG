// Package mongo implements post/application/usecase ports against MongoDB.
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

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/post/application/usecase"
)

// collectionName is the MongoDB collection backing the post read model.
const collectionName = "posts"

// operationTimeout bounds every Mongo call made by Repository.
const operationTimeout = 5 * time.Second

// ErrVersionConflict is returned by Update when the stored document's
// version does not match UpdatePostInput.ExpectedVersion, meaning the
// document was modified by another writer since the version was read.
var ErrVersionConflict = errors.New("post version conflict")

// postMediaDocument is one stored media element. DurationMS holds the event's
// duration field unchanged; it already is in milliseconds.
type postMediaDocument struct {
	ID           string   `bson:"id"`
	URL          string   `bson:"url"`
	ThumbnailURL *string  `bson:"thumbnail_url"`
	MediaType    string   `bson:"media_type"`
	DurationMS   *int64   `bson:"duration_ms"`
	TaggedUsers  []string `bson:"tagged_users"`
	Order        int      `bson:"order"`
}

// postStatsDocument holds the post's counters. Only the Adjust*Count methods
// write them; see adjustStat.
type postStatsDocument struct {
	LikeCount    int64 `bson:"like_count"`
	CommentCount int64 `bson:"comment_count"`
	ShareCount   int64 `bson:"share_count"`
}

// postDocument is the persisted shape of a post read model entry.
type postDocument struct {
	PostID      string              `bson:"post_id"`
	UserID      string              `bson:"user_id"`
	CollabID    string              `bson:"collab_id"`
	PostType    string              `bson:"post_type"`
	Description string              `bson:"description"`
	Tags        []string            `bson:"tags"`
	Media       []postMediaDocument `bson:"media"`
	Stats       postStatsDocument   `bson:"stats"`
	CreatedAt   time.Time           `bson:"created_at"`
	UpdatedAt   time.Time           `bson:"updated_at"`
	Version     int64               `bson:"version"`
}

// Repository implements usecase.CreatePostRepository,
// usecase.UpdatePostRepository, usecase.LinkPostCollabRepository, and
// usecase.DeletePostRepository against MongoDB. Its Adjust*Count methods also
// implement the post-stats ports of the like, comment and share usecases.
type Repository struct {
	collection *mongo.Collection
}

// NewRepository creates a Repository backed by db's posts collection.
func NewRepository(db *mongo.Database) *Repository {
	return &Repository{collection: db.Collection(collectionName)}
}

// EnsureIndexes creates the unique index on post_id required to make Insert
// idempotent under concurrent or retried deliveries of the same creation
// event.
func (r *Repository) EnsureIndexes(ctx context.Context) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	indexModel := mongo.IndexModel{
		Keys:    bson.D{{Key: "post_id", Value: 1}},
		Options: options.Index().SetUnique(true),
	}

	if _, err := r.collection.Indexes().CreateOne(ctx, indexModel); err != nil {
		return fmt.Errorf("ensure posts indexes: %w", err)
	}

	return nil
}

// Insert writes input as a new post read model document at version 1. A
// duplicate delivery of the same post-creation event is already filtered
// out upstream by the idempotency repository before the use case runs, but
// a crash between MarkProcessed and this write makes a retry of the same
// creation event theoretically possible; when that retry hits the unique
// index on post_id, Insert treats the duplicate-key error as an
// already-applied insert and returns nil.
func (r *Repository) Insert(ctx context.Context, input usecase.CreatePostInput) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	collabID := ""
	if input.CollabID != uuid.Nil {
		collabID = input.CollabID.String()
	}

	doc := postDocument{
		PostID:      input.PostID.String(),
		UserID:      input.UserID.String(),
		CollabID:    collabID,
		PostType:    input.PostType,
		Description: input.Description,
		Tags:        input.Tags,
		Media:       toPostMediaDocuments(input.Media),
		CreatedAt:   input.CreatedAt,
		UpdatedAt:   input.UpdatedAt,
		Version:     1,
	}

	if _, err := r.collection.InsertOne(ctx, doc); err != nil {
		if mongo.IsDuplicateKeyError(err) {
			return nil
		}
		return fmt.Errorf("insert post %s: %w", input.PostID, err)
	}

	return nil
}

// GetVersion returns the current version of the post read model document
// identified by postID, and whether it exists at all. UpdatePostUsecase
// uses it to fill in UpdatePostInput.ExpectedVersion before calling Update,
// since PostUpdatedEvent carries no version of its own.
func (r *Repository) GetVersion(ctx context.Context, postID uuid.UUID) (int64, bool, error) {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	var doc struct {
		Version int64 `bson:"version"`
	}
	err := r.collection.FindOne(ctx, bson.D{{Key: "post_id", Value: postID.String()}}).Decode(&doc)
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			return 0, false, nil
		}
		return 0, false, fmt.Errorf("get post %s version: %w", postID, err)
	}

	return doc.Version, true, nil
}

// Update applies an optimistic-concurrency update to the post read model
// document identified by input.PostID. It only succeeds when the stored
// document's version equals input.ExpectedVersion; on success the changed
// fields are set and the version is incremented by one. If no document
// matched (either the post does not exist or its version has already moved
// on), Update returns ErrVersionConflict.
func (r *Repository) Update(ctx context.Context, input usecase.UpdatePostInput) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	filter := bson.D{
		{Key: "post_id", Value: input.PostID.String()},
		{Key: "version", Value: input.ExpectedVersion},
	}
	fields := bson.D{
		{Key: "post_type", Value: input.PostType},
		{Key: "description", Value: input.Description},
		{Key: "tags", Value: input.Tags},
		{Key: "updated_at", Value: input.UpdatedAt},
	}
	if input.Media != nil {
		fields = append(fields, bson.E{Key: "media", Value: toPostMediaDocuments(input.Media)})
	}
	update := bson.D{
		{Key: "$set", Value: fields},
		{Key: "$inc", Value: bson.D{{Key: "version", Value: int64(1)}}},
	}

	result, err := r.collection.UpdateOne(ctx, filter, update)
	if err != nil {
		return fmt.Errorf("update post %s: %w", input.PostID, err)
	}
	if result.ModifiedCount == 0 {
		return fmt.Errorf("update post %s: %w", input.PostID, ErrVersionConflict)
	}

	return nil
}

// LinkCollab applies an optimistic-concurrency update that sets only the
// collab_id of the post read model document identified by
// input.PostID. It only succeeds when the stored document's version equals
// input.ExpectedVersion; on success collab_id is set and the version is
// incremented by one. If no document matched (either the post does not
// exist or its version has already moved on), LinkCollab returns
// ErrVersionConflict. This is a separate write path from Update: linking a
// collab writes a foreign key, it never sources or overwrites post content
// fields.
func (r *Repository) LinkCollab(ctx context.Context, input usecase.LinkPostCollabInput) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	filter := bson.D{
		{Key: "post_id", Value: input.PostID.String()},
		{Key: "version", Value: input.ExpectedVersion},
	}
	update := bson.D{
		{Key: "$set", Value: bson.D{
			{Key: "collab_id", Value: input.CollabID.String()},
		}},
		{Key: "$inc", Value: bson.D{{Key: "version", Value: int64(1)}}},
	}

	result, err := r.collection.UpdateOne(ctx, filter, update)
	if err != nil {
		return fmt.Errorf("link collab onto post %s: %w", input.PostID, err)
	}
	if result.ModifiedCount == 0 {
		return fmt.Errorf("link collab onto post %s: %w", input.PostID, ErrVersionConflict)
	}

	return nil
}

// Delete idempotently removes postID's post read model document. Deleting
// an already-absent document is not an error.
func (r *Repository) Delete(ctx context.Context, postID uuid.UUID) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	filter := bson.D{{Key: "post_id", Value: postID.String()}}
	if _, err := r.collection.DeleteOne(ctx, filter); err != nil {
		return fmt.Errorf("delete post %s: %w", postID, err)
	}

	return nil
}

// AdjustLikeCount adds delta to stats.like_count. See adjustStat.
func (r *Repository) AdjustLikeCount(ctx context.Context, postID uuid.UUID, delta int64) (bool, error) {
	return r.adjustStat(ctx, postID, "like_count", delta)
}

// AdjustCommentCount adds delta to stats.comment_count. See adjustStat.
func (r *Repository) AdjustCommentCount(ctx context.Context, postID uuid.UUID, delta int64) (bool, error) {
	return r.adjustStat(ctx, postID, "comment_count", delta)
}

// AdjustShareCount adds delta to stats.share_count. See adjustStat.
func (r *Repository) AdjustShareCount(ctx context.Context, postID uuid.UUID, delta int64) (bool, error) {
	return r.adjustStat(ctx, postID, "share_count", delta)
}

// adjustStat atomically adds delta to one counter, flooring it at 0, and reports
// whether the post exists. An aggregation-pipeline update expresses the floor in
// one write and treats a missing counter as 0. It does not touch version: the
// counters are written by no other path, and bumping version on every like would
// make PostUpdatedEvent's optimistic-concurrency write conflict on popular posts.
func (r *Repository) adjustStat(ctx context.Context, postID uuid.UUID, counter string, delta int64) (bool, error) {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	path := "stats." + counter
	update := mongo.Pipeline{
		{{Key: "$set", Value: bson.D{
			{Key: path, Value: bson.D{{Key: "$max", Value: bson.A{
				int64(0),
				bson.D{{Key: "$add", Value: bson.A{
					bson.D{{Key: "$ifNull", Value: bson.A{"$" + path, int64(0)}}},
					delta,
				}}},
			}}}},
		}}},
	}

	result, err := r.collection.UpdateOne(ctx, bson.D{{Key: "post_id", Value: postID.String()}}, update)
	if err != nil {
		return false, fmt.Errorf("adjust post %s %s: %w", postID, counter, err)
	}

	return result.MatchedCount > 0, nil
}

// toPostMediaDocuments maps media to its stored shape, always as a list (never
// null), with an empty list for null tagged users.
func toPostMediaDocuments(media []usecase.PostMedia) []postMediaDocument {
	documents := make([]postMediaDocument, 0, len(media))
	for _, element := range media {
		taggedUsers := element.TaggedUsers
		if taggedUsers == nil {
			taggedUsers = []string{}
		}
		documents = append(documents, postMediaDocument{
			ID:           element.ID.String(),
			URL:          element.URL,
			ThumbnailURL: element.ThumbnailURL,
			MediaType:    element.MediaType,
			DurationMS:   element.Duration,
			TaggedUsers:  taggedUsers,
			Order:        element.Order,
		})
	}

	return documents
}
