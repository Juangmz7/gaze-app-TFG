package mongo_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/testcontainers/testcontainers-go/modules/mongodb"
	"go.mongodb.org/mongo-driver/v2/bson"
	mongodriver "go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/comment/application/usecase"
	commentmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/comment/infrastructure/mongo"
)

type commentDocument struct {
	CommentID string `bson:"comment_id"`
	PostID    string `bson:"post_id"`
	UserID    string `bson:"user_id"`
	Content   string `bson:"content"`
	Version   int64  `bson:"version"`
}

func TestRepository_Insert_PersistsTheCommentReadModelAtVersionOne(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := commentmongo.NewRepository(db)

	input := usecase.RecordCommentInput{
		CommentID: uuid.New(),
		PostID:    uuid.New(),
		UserID:    uuid.New(),
		Content:   "nice post",
		CreatedAt: time.Now().UTC(),
	}

	if err := repository.Insert(ctx, input); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	got := findComment(t, ctx, db, input.CommentID)
	if got.Content != input.Content {
		t.Fatalf("stored document Content = %q, want %q", got.Content, input.Content)
	}
	if got.PostID != input.PostID.String() {
		t.Fatalf("stored document PostID = %q, want %q", got.PostID, input.PostID.String())
	}
	if got.Version != 1 {
		t.Fatalf("stored document Version = %d, want 1", got.Version)
	}
}

// TestRepository_Insert_IsIdempotentOnARetriedInsertOfTheSameCommentID
// proves the crash-and-retry race named by Insert's doc comment: a unique
// index on comment_id makes a second Insert of the same business id a no-op
// (duplicate key treated as already-applied) rather than a second document
// or an error, so EnsureIndexes must run first.
func TestRepository_Insert_IsIdempotentOnARetriedInsertOfTheSameCommentID(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := commentmongo.NewRepository(db)
	if err := repository.EnsureIndexes(ctx); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}

	commentID := uuid.New()
	input := usecase.RecordCommentInput{CommentID: commentID, PostID: uuid.New(), UserID: uuid.New(), Content: "first", CreatedAt: time.Now().UTC()}
	if err := repository.Insert(ctx, input); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}
	if err := repository.Insert(ctx, input); err != nil {
		t.Fatalf("Insert() error = %v, want nil on a retried insert of the same comment id", err)
	}

	count, err := db.Collection("post_comments").CountDocuments(ctx, bson.D{{Key: "comment_id", Value: commentID.String()}})
	if err != nil {
		t.Fatalf("CountDocuments() error = %v", err)
	}
	if count != 1 {
		t.Fatalf("CountDocuments() = %d, want exactly 1 document for comment_id %s", count, commentID)
	}
}

func TestRepository_Update_SucceedsAndIncrementsVersionWhenExpectedVersionMatches(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := commentmongo.NewRepository(db)

	commentID := uuid.New()
	if err := repository.Insert(ctx, usecase.RecordCommentInput{CommentID: commentID, PostID: uuid.New(), UserID: uuid.New(), Content: "first", CreatedAt: time.Now().UTC()}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	update := usecase.UpdateCommentInput{CommentID: commentID, ExpectedVersion: 1, PostID: uuid.New(), UserID: uuid.New(), Content: "edited"}
	if err := repository.Update(ctx, update); err != nil {
		t.Fatalf("Update() error = %v", err)
	}

	got := findComment(t, ctx, db, commentID)
	if got.Content != "edited" {
		t.Fatalf("stored document Content = %q, want %q", got.Content, "edited")
	}
	if got.Version != 2 {
		t.Fatalf("stored document Version = %d, want 2", got.Version)
	}
}

func TestRepository_Update_ReturnsVersionConflictAndDoesNotModifyWhenExpectedVersionIsStale(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := commentmongo.NewRepository(db)

	commentID := uuid.New()
	if err := repository.Insert(ctx, usecase.RecordCommentInput{CommentID: commentID, PostID: uuid.New(), UserID: uuid.New(), Content: "first", CreatedAt: time.Now().UTC()}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	update := usecase.UpdateCommentInput{CommentID: commentID, ExpectedVersion: 99, PostID: uuid.New(), UserID: uuid.New(), Content: "edited"}
	err := repository.Update(ctx, update)
	if !errors.Is(err, usecase.ErrCommentVersionConflict) {
		t.Fatalf("Update() error = %v, want it to wrap ErrVersionConflict", err)
	}

	got := findComment(t, ctx, db, commentID)
	if got.Content != "first" {
		t.Fatalf("stored document Content = %q, want unchanged %q", got.Content, "first")
	}
	if got.Version != 1 {
		t.Fatalf("stored document Version = %d, want unchanged 1", got.Version)
	}
}

func TestRepository_GetVersion_ReturnsTheStoredVersionWhenTheCommentExists(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := commentmongo.NewRepository(db)

	commentID := uuid.New()
	if err := repository.Insert(ctx, usecase.RecordCommentInput{CommentID: commentID, PostID: uuid.New(), UserID: uuid.New(), Content: "first", CreatedAt: time.Now().UTC()}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	version, found, err := repository.GetVersion(ctx, commentID)
	if err != nil {
		t.Fatalf("GetVersion() error = %v", err)
	}
	if !found {
		t.Fatal("GetVersion() found = false, want true for an existing comment")
	}
	if version != 1 {
		t.Fatalf("GetVersion() version = %d, want 1", version)
	}
}

func TestRepository_GetVersion_ReturnsNotFoundWhenTheCommentWasNeverProjected(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := commentmongo.NewRepository(db)

	_, found, err := repository.GetVersion(ctx, uuid.New())
	if err != nil {
		t.Fatalf("GetVersion() error = %v", err)
	}
	if found {
		t.Fatal("GetVersion() found = true, want false for a comment that was never projected")
	}
}

func TestRepository_FindContentAndUpdatedAt_ReturnsTheStoredFieldsWhenTheCommentExists(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := commentmongo.NewRepository(db)

	commentID := uuid.New()
	if err := repository.Insert(ctx, usecase.RecordCommentInput{CommentID: commentID, PostID: uuid.New(), UserID: uuid.New(), Content: "first", CreatedAt: time.Now().UTC()}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	updatedAt := time.Now().UTC().Add(time.Hour).Truncate(time.Millisecond)
	update := usecase.UpdateCommentInput{CommentID: commentID, ExpectedVersion: 1, PostID: uuid.New(), UserID: uuid.New(), Content: "edited", UpdatedAt: updatedAt}
	if err := repository.Update(ctx, update); err != nil {
		t.Fatalf("Update() error = %v", err)
	}

	content, gotUpdatedAt, found, err := repository.FindContentAndUpdatedAt(ctx, commentID)
	if err != nil {
		t.Fatalf("FindContentAndUpdatedAt() error = %v", err)
	}
	if !found {
		t.Fatal("FindContentAndUpdatedAt() found = false, want true for an existing comment")
	}
	if content != "edited" {
		t.Fatalf("FindContentAndUpdatedAt() content = %q, want %q", content, "edited")
	}
	if !gotUpdatedAt.Equal(updatedAt) {
		t.Fatalf("FindContentAndUpdatedAt() updatedAt = %v, want %v", gotUpdatedAt, updatedAt)
	}
}

func TestRepository_FindContentAndUpdatedAt_ReturnsNotFoundWhenTheCommentWasNeverProjected(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := commentmongo.NewRepository(db)

	_, _, found, err := repository.FindContentAndUpdatedAt(ctx, uuid.New())
	if err != nil {
		t.Fatalf("FindContentAndUpdatedAt() error = %v", err)
	}
	if found {
		t.Fatal("FindContentAndUpdatedAt() found = true, want false for a comment that was never projected")
	}
}

func TestRepository_Delete_RemovesAnExistingCommentReadModelDocument(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := commentmongo.NewRepository(db)

	commentID := uuid.New()
	if err := repository.Insert(ctx, usecase.RecordCommentInput{CommentID: commentID, PostID: uuid.New(), UserID: uuid.New(), Content: "first", CreatedAt: time.Now().UTC()}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	if err := repository.Delete(ctx, commentID); err != nil {
		t.Fatalf("Delete() error = %v", err)
	}

	count, err := db.Collection("post_comments").CountDocuments(ctx, bson.D{{Key: "comment_id", Value: commentID.String()}})
	if err != nil {
		t.Fatalf("CountDocuments() error = %v", err)
	}
	if count != 0 {
		t.Fatalf("CountDocuments() = %d, want 0 after Delete", count)
	}
}

func TestRepository_Delete_IsANoOpWhenTheCommentWasNeverProjected(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := commentmongo.NewRepository(db)

	if err := repository.Delete(ctx, uuid.New()); err != nil {
		t.Fatalf("Delete() error = %v, want nil for an absent document", err)
	}
}

func findComment(t *testing.T, ctx context.Context, db *mongodriver.Database, commentID uuid.UUID) commentDocument {
	t.Helper()

	var doc commentDocument
	err := db.Collection("post_comments").FindOne(ctx, bson.D{{Key: "comment_id", Value: commentID.String()}}).Decode(&doc)
	if err != nil {
		t.Fatalf("FindOne() error = %v", err)
	}

	return doc
}

func testDatabase(t *testing.T, ctx context.Context) *mongodriver.Database {
	t.Helper()

	container, err := mongodb.Run(ctx, "mongo:7")
	if err != nil {
		t.Fatalf("mongodb.Run() error = %v", err)
	}
	t.Cleanup(func() {
		if terminateErr := container.Terminate(context.Background()); terminateErr != nil {
			t.Logf("container.Terminate() error = %v", terminateErr)
		}
	})

	connectionString, err := container.ConnectionString(ctx)
	if err != nil {
		t.Fatalf("container.ConnectionString() error = %v", err)
	}

	client, err := mongodriver.Connect(options.Client().ApplyURI(connectionString))
	if err != nil {
		t.Fatalf("mongo.Connect() error = %v", err)
	}
	t.Cleanup(func() {
		if disconnectErr := client.Disconnect(context.Background()); disconnectErr != nil {
			t.Logf("client.Disconnect() error = %v", disconnectErr)
		}
	})

	return client.Database("post_query_comment_repository_test")
}
