package mongo_test

import (
	"context"
	"slices"
	"testing"
	"time"

	"github.com/google/uuid"
	"go.mongodb.org/mongo-driver/v2/bson"
	mongodriver "go.mongodb.org/mongo-driver/v2/mongo"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/comment/application/usecase"
	commentmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/comment/infrastructure/mongo"
)

func storedCounters(t *testing.T, ctx context.Context, db *mongodriver.Database, commentID string) (bson.M, bool) {
	t.Helper()

	var doc bson.M
	err := db.Collection("post_comments").FindOne(ctx, bson.D{{Key: "comment_id", Value: commentID}}).Decode(&doc)
	if err != nil {
		t.Fatalf("FindOne() error = %v", err)
	}
	_, hasLikes := doc["like_count"]
	_, hasReplies := doc["reply_count"]
	return doc, hasLikes && hasReplies
}

// TestCommentRepository_Counters shares one container between its subtests.
func TestCommentRepository_Counters(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	db := testDatabase(t, ctx)
	comments := commentmongo.NewRepository(db)
	likes := commentmongo.NewCommentLikeRepository(db)
	for _, ensure := range []func(context.Context) error{comments.EnsureIndexes, likes.EnsureIndexes} {
		if err := ensure(ctx); err != nil {
			t.Fatalf("EnsureIndexes() error = %v", err)
		}
	}
	insert := func(t *testing.T, replyTo uuid.UUID) uuid.UUID {
		t.Helper()
		commentID := uuid.New()
		if _, err := comments.Insert(ctx, usecase.RecordCommentInput{CommentID: commentID, PostID: uuid.New(), UserID: uuid.New(), Content: "Nice", ReplyTo: replyTo, CreatedAt: time.Now().UTC()}); err != nil {
			t.Fatalf("Insert() error = %v", err)
		}
		return commentID
	}

	t.Run("a new comment starts with both counters at zero", func(t *testing.T) {
		doc, complete := storedCounters(t, ctx, db, insert(t, uuid.Nil).String())
		if !complete || doc["like_count"] != int64(0) || doc["reply_count"] != int64(0) {
			t.Fatalf("stored = %v, want like_count and reply_count 0", doc)
		}
	})

	t.Run("counters move by delta, never below zero, without bumping version", func(t *testing.T) {
		commentID := insert(t, uuid.Nil)
		for _, delta := range []int64{1, 1, -1, -1, -1} {
			if found, err := comments.AdjustLikeCount(ctx, commentID, delta); err != nil || !found {
				t.Fatalf("AdjustLikeCount(%d) = %v, %v", delta, found, err)
			}
		}
		if found, err := comments.AdjustReplyCount(ctx, commentID, -1); err != nil || !found {
			t.Fatalf("AdjustReplyCount() = %v, %v", found, err)
		}
		doc, _ := storedCounters(t, ctx, db, commentID.String())
		if doc["like_count"] != int64(0) || doc["reply_count"] != int64(0) || doc["version"] != int64(1) {
			t.Fatalf("stored = %v, want counters floored at 0 and version 1", doc)
		}
		if found, err := comments.AdjustLikeCount(ctx, uuid.New(), 1); err != nil || found {
			t.Fatalf("AdjustLikeCount(unknown) = %v, %v, want not found", found, err)
		}
	})

	t.Run("a parent counts the replies stored before it", func(t *testing.T) {
		parentID := uuid.New()
		insert(t, parentID)
		insert(t, parentID)
		if _, err := comments.Insert(ctx, usecase.RecordCommentInput{CommentID: parentID, PostID: uuid.New(), UserID: uuid.New(), Content: "Parent", CreatedAt: time.Now().UTC()}); err != nil {
			t.Fatalf("Insert() error = %v", err)
		}
		if doc, _ := storedCounters(t, ctx, db, parentID.String()); doc["reply_count"] != int64(2) {
			t.Fatalf("reply_count = %v, want 2", doc["reply_count"])
		}
	})

	t.Run("delete reports the parent of a reply", func(t *testing.T) {
		parentID := insert(t, uuid.Nil)
		replyID := insert(t, parentID)

		removed, replyTo, err := comments.Delete(ctx, replyID)
		if err != nil || !removed || replyTo != parentID {
			t.Fatalf("Delete(reply) = %v, %v, %v, want removed with the parent", removed, replyTo, err)
		}
		removed, replyTo, err = comments.Delete(ctx, parentID)
		if err != nil || !removed || replyTo != uuid.Nil {
			t.Fatalf("Delete(top-level) = %v, %v, %v, want removed with no parent", removed, replyTo, err)
		}
	})

	t.Run("comment likes are one per user and report what they changed", func(t *testing.T) {
		input := usecase.RecordCommentLikeInput{CommentID: uuid.New(), UserID: uuid.New(), CreatedAt: time.Now().UTC()}
		if inserted, err := likes.Insert(ctx, input); err != nil || !inserted {
			t.Fatalf("Insert() = %v, %v, want inserted", inserted, err)
		}
		if inserted, err := likes.Insert(ctx, input); err != nil || inserted {
			t.Fatalf("Insert() again = %v, %v, want not inserted", inserted, err)
		}
		if removed, err := likes.Delete(ctx, input.CommentID, input.UserID); err != nil || !removed {
			t.Fatalf("Delete() = %v, %v, want removed", removed, err)
		}
		if removed, err := likes.Delete(ctx, input.CommentID, input.UserID); err != nil || removed {
			t.Fatalf("Delete() again = %v, %v, want nothing removed", removed, err)
		}
	})

	t.Run("the list indexes and the unique comment like index exist", func(t *testing.T) {
		names := indexNames(t, ctx, db, "post_comments")
		for _, want := range []string{commentmongo.TopLevelCommentsIndex, commentmongo.RepliesIndex, "comment_id_1"} {
			if !slices.Contains(names, want) {
				t.Fatalf("post_comments indexes = %v, want %s", names, want)
			}
		}
		if names := indexNames(t, ctx, db, "comment_likes"); !slices.Contains(names, "comment_id_1_user_id_1") {
			t.Fatalf("comment_likes indexes = %v", names)
		}
	})
}

func TestCommentRepository_BackfillCountersGivesOldCommentsTheirCountersOnce(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	db := testDatabase(t, ctx)
	comments := commentmongo.NewRepository(db)
	parentID, replyID, loneID := uuid.NewString(), uuid.NewString(), uuid.NewString()
	// Documents as earlier versions stored them: no counters.
	old := func(commentID, replyTo string) bson.D {
		return bson.D{{Key: "comment_id", Value: commentID}, {Key: "post_id", Value: uuid.NewString()}, {Key: "reply_to", Value: replyTo}, {Key: "version", Value: int64(1)}}
	}
	if _, err := db.Collection("post_comments").InsertMany(ctx, []any{old(parentID, ""), old(replyID, parentID), old(loneID, "")}); err != nil {
		t.Fatalf("InsertMany() error = %v", err)
	}

	if err := comments.BackfillCounters(ctx); err != nil {
		t.Fatalf("BackfillCounters() error = %v", err)
	}

	for commentID, wantReplies := range map[string]int64{parentID: 1, replyID: 0, loneID: 0} {
		doc, complete := storedCounters(t, ctx, db, commentID)
		if !complete || doc["like_count"] != int64(0) || doc["reply_count"] != wantReplies {
			t.Fatalf("comment %s = %v, want like_count 0 and reply_count %d", commentID, doc, wantReplies)
		}
	}

	// The marker makes a later bootstrap leave everything alone.
	if _, err := db.Collection("post_comments").InsertOne(ctx, old(uuid.NewString(), "")); err != nil {
		t.Fatalf("InsertOne() error = %v", err)
	}
	if err := comments.BackfillCounters(ctx); err != nil {
		t.Fatalf("BackfillCounters() again error = %v", err)
	}
	missing, err := db.Collection("post_comments").CountDocuments(ctx, bson.D{{Key: "like_count", Value: bson.D{{Key: "$exists", Value: false}}}})
	if err != nil || missing != 1 {
		t.Fatalf("documents without counters = %d, %v, want the one written after the migration ran", missing, err)
	}
}

func indexNames(t *testing.T, ctx context.Context, db *mongodriver.Database, collection string) []string {
	t.Helper()

	cursor, err := db.Collection(collection).Indexes().List(ctx)
	if err != nil {
		t.Fatalf("List() error = %v", err)
	}
	var indexes []struct {
		Name string `bson:"name"`
	}
	if err := cursor.All(ctx, &indexes); err != nil {
		t.Fatalf("All() error = %v", err)
	}
	names := make([]string, 0, len(indexes))
	for _, index := range indexes {
		names = append(names, index.Name)
	}
	return names
}
