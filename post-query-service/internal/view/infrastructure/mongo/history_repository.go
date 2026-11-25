// Package mongo implements view/application/usecase ports against MongoDB.
package mongo

import (
	"context"
	"errors"
	"fmt"
	"math"
	"time"

	"github.com/google/uuid"
	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/view/application/usecase"
)

// CollectionName is the MongoDB collection of the viewing history: one document per
// (user_id, post_id).
const CollectionName = "view_history"

// UserHistoryIndex is the name of the view_history index that serves GET
// /api/content/me/history (task 50): the caller's history most recently viewed first,
// whose sort it matches exactly, so every keyset page is an index seek.
const UserHistoryIndex = "user_id_1_last_viewed_at_-1_post_id_-1"

// historyTTLIndexName is the name MongoDB gives the TTL index on last_viewed_at.
const historyTTLIndexName = "last_viewed_at_1"

// operationTimeout bounds every Mongo call made by HistoryRepository.
const operationTimeout = 5 * time.Second

// viewDayLayout is the layout of counted_day, the UTC day of a view (YYYY-MM-DD).
const viewDayLayout = "2006-01-02"

// mongoIndexOptionsConflictCode is the error createIndexes returns when an index
// already exists with other options, here a changed retention.
const mongoIndexOptionsConflictCode = 85

// historyDocument is the persisted shape of a viewing history row. It has no version:
// last_viewed_at = max(stored, new) gives the same result in any delivery order, and
// the daily view count (counted_day, counted_views) changes only through one atomic
// pipeline update (see Record), so there is nothing to gate. Rows written before task
// 66 have no counted_day/counted_views, which Record reads as no views counted.
type historyDocument struct {
	UserID       string    `bson:"user_id"`
	PostID       string    `bson:"post_id"`
	LastViewedAt time.Time `bson:"last_viewed_at"`
	// CountedDay is the UTC day (YYYY-MM-DD) whose views CountedViews holds.
	CountedDay string `bson:"counted_day"`
	// CountedViews is how many views of CountedDay were counted on the post.
	CountedViews int `bson:"counted_views"`
}

// historyCount is the daily view count of a row as it was before Record's update.
// Missing fields decode as "" and 0.
type historyCount struct {
	CountedDay   string `bson:"counted_day"`
	CountedViews int    `bson:"counted_views"`
}

// HistoryRepository implements usecase.RecordPostViewRepository against MongoDB.
type HistoryRepository struct {
	collection *mongo.Collection
}

// NewHistoryRepository creates a HistoryRepository backed by db's view_history
// collection.
func NewHistoryRepository(db *mongo.Database) *HistoryRepository {
	return &HistoryRepository{collection: db.Collection(CollectionName)}
}

// EnsureIndexes creates the view_history indexes:
//   - unique (user_id, post_id): one row per user and post, so the collection is
//     bounded by the posts a user saw, not by the views.
//   - UserHistoryIndex: the history tab's keyset pages.
//   - TTL on last_viewed_at, expiring after retention. A changed retention is applied
//     in place (collMod), since createIndexes refuses to change it and a new value
//     must not stop the service from starting.
func (r *HistoryRepository) EnsureIndexes(ctx context.Context, retention time.Duration) error {
	if retention < time.Second || retention/time.Second > math.MaxInt32 {
		return fmt.Errorf("ensure %s indexes: retention %s must be between 1s and %d seconds", CollectionName, retention, math.MaxInt32)
	}
	seconds := int32(retention / time.Second)

	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	indexes := []mongo.IndexModel{
		{
			Keys:    bson.D{{Key: "user_id", Value: 1}, {Key: "post_id", Value: 1}},
			Options: options.Index().SetUnique(true),
		},
		{
			Keys:    bson.D{{Key: "user_id", Value: 1}, {Key: "last_viewed_at", Value: -1}, {Key: "post_id", Value: -1}},
			Options: options.Index().SetName(UserHistoryIndex),
		},
	}
	if _, err := r.collection.Indexes().CreateMany(ctx, indexes); err != nil {
		return fmt.Errorf("ensure %s indexes: %w", CollectionName, err)
	}

	return r.ensureTTLIndex(ctx, seconds)
}

func (r *HistoryRepository) ensureTTLIndex(ctx context.Context, seconds int32) error {
	ttlIndex := mongo.IndexModel{
		Keys:    bson.D{{Key: "last_viewed_at", Value: 1}},
		Options: options.Index().SetName(historyTTLIndexName).SetExpireAfterSeconds(seconds),
	}
	_, err := r.collection.Indexes().CreateOne(ctx, ttlIndex)
	if err == nil {
		return nil
	}
	var commandErr mongo.CommandError
	if !errors.As(err, &commandErr) || commandErr.Code != mongoIndexOptionsConflictCode {
		return fmt.Errorf("ensure %s ttl index: %w", CollectionName, err)
	}

	collMod := bson.D{
		{Key: "collMod", Value: CollectionName},
		{Key: "index", Value: bson.D{
			{Key: "name", Value: historyTTLIndexName},
			{Key: "expireAfterSeconds", Value: seconds},
		}},
	}
	if err := r.collection.Database().RunCommand(ctx, collMod).Err(); err != nil {
		return fmt.Errorf("change %s ttl index: %w", CollectionName, err)
	}

	return nil
}

// Record keeps one row per (user, post) with last_viewed_at = max(stored,
// view.ViewedAt) and counts the view against view.DailyLimit for view.Day, in one
// single-document write.
//
// The existing row is updated with an aggregation pipeline that decides on the server,
// atomically, from the stored values (see dailyCountPipeline): a new day restarts the
// count at 1, the same day under the limit adds 1, and anything else (the limit
// reached, or a day older than counted_day) leaves the count alone. The update returns
// the row as it was before it, and viewCountDecision applies the same rules to that
// image to report what the server did: the decision is never written back from Go. In
// a transaction, a concurrent writer of the same row makes this update a write
// conflict, so the driver re-runs the handler and the decision is computed again.
//
// It runs inside the handler's transaction, where a duplicate-key error aborts the
// whole transaction, so a row is inserted (already counting this view, the first of
// its day) only when none matched. A duplicate-key error can then only come from a
// concurrent first view of the same post: it is returned as a retryable error, and the
// redelivery updates the row that writer created.
func (r *HistoryRepository) Record(ctx context.Context, view usecase.RecordPostViewHistory) (usecase.ViewCountDecision, error) {
	if err := validateHistoryView(view); err != nil {
		return 0, fmt.Errorf("record view history: %w", err)
	}

	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	viewedAt := view.ViewedAt.UTC()
	filter := bson.D{
		{Key: "user_id", Value: view.UserID.String()},
		{Key: "post_id", Value: view.PostID.String()},
	}
	opts := options.FindOneAndUpdate().
		SetReturnDocument(options.Before).
		SetProjection(bson.D{{Key: "_id", Value: 0}, {Key: "counted_day", Value: 1}, {Key: "counted_views", Value: 1}})
	var before historyCount
	err := r.collection.FindOneAndUpdate(ctx, filter, dailyCountPipeline(viewedAt, view.Day, view.DailyLimit), opts).Decode(&before)
	if err == nil {
		return viewCountDecision(before, view.Day, view.DailyLimit), nil
	}
	if !errors.Is(err, mongo.ErrNoDocuments) {
		return 0, fmt.Errorf("update view history of user %s post %s: %w", view.UserID, view.PostID, err)
	}

	doc := historyDocument{UserID: view.UserID.String(), PostID: view.PostID.String(), LastViewedAt: viewedAt, CountedDay: view.Day, CountedViews: 1}
	if _, err := r.collection.InsertOne(ctx, doc); err != nil {
		return 0, fmt.Errorf("insert view history of user %s post %s: %w", view.UserID, view.PostID, err)
	}

	return usecase.ViewCounted, nil
}

// dailyCountPipeline is the update of an existing history row. Every expression of a
// $set stage reads the row as it was before the stage, so counted_day and
// counted_views are both decided from the stored values. A missing counted_day reads
// as "" and a missing counted_views as 0, which sort before any day and count nothing.
func dailyCountPipeline(viewedAt time.Time, day string, limit int) mongo.Pipeline {
	storedDay := bson.D{{Key: "$ifNull", Value: bson.A{"$counted_day", ""}}}
	storedViews := bson.D{{Key: "$ifNull", Value: bson.A{"$counted_views", 0}}}
	newDay := bson.D{{Key: "$lt", Value: bson.A{storedDay, day}}}
	sameDayUnderLimit := bson.D{{Key: "$and", Value: bson.A{
		bson.D{{Key: "$eq", Value: bson.A{storedDay, day}}},
		bson.D{{Key: "$lt", Value: bson.A{storedViews, limit}}},
	}}}

	return mongo.Pipeline{
		{{Key: "$set", Value: bson.D{
			{Key: "last_viewed_at", Value: bson.D{{Key: "$max", Value: bson.A{"$last_viewed_at", viewedAt}}}},
			{Key: "counted_day", Value: bson.D{{Key: "$cond", Value: bson.A{newDay, day, "$counted_day"}}}},
			{Key: "counted_views", Value: bson.D{{Key: "$switch", Value: bson.D{
				{Key: "branches", Value: bson.A{
					bson.D{{Key: "case", Value: newDay}, {Key: "then", Value: 1}},
					bson.D{{Key: "case", Value: sameDayUnderLimit}, {Key: "then", Value: bson.D{{Key: "$add", Value: bson.A{storedViews, 1}}}}},
				}},
				{Key: "default", Value: "$counted_views"},
			}}}},
		}}},
	}
}

// viewCountDecision reports what dailyCountPipeline did to a row whose stored count
// was before. It must follow the same rules as the pipeline.
func viewCountDecision(before historyCount, day string, limit int) usecase.ViewCountDecision {
	switch {
	case before.CountedDay < day:
		return usecase.ViewCounted
	case before.CountedDay == day && before.CountedViews < limit:
		return usecase.ViewCounted
	case before.CountedDay == day:
		return usecase.ViewOverDailyLimit
	default:
		return usecase.ViewFromPastDay
	}
}

// validateHistoryView checks what Record relies on: the ids, a view time, a day that is
// the view's UTC day in counted_day's layout, and a limit of at least 1.
func validateHistoryView(view usecase.RecordPostViewHistory) error {
	if view.UserID == uuid.Nil || view.PostID == uuid.Nil {
		return errors.New("user id and post id are required")
	}
	if view.ViewedAt.IsZero() {
		return errors.New("viewed at is required")
	}
	if want := view.ViewedAt.UTC().Format(viewDayLayout); view.Day != want {
		return fmt.Errorf("day %q is not the UTC day %s of the view", view.Day, want)
	}
	if view.DailyLimit < 1 {
		return fmt.Errorf("daily limit %d must be at least 1", view.DailyLimit)
	}

	return nil
}
