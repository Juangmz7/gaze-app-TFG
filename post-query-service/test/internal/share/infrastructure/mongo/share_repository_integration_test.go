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

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/share/application/usecase"
	sharemongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/share/infrastructure/mongo"
)

type shareDocument struct {
	ShareID string `bson:"share_id"`
	PostID  string `bson:"post_id"`
	UserID  string `bson:"user_id"`
	Version int64  `bson:"version"`
}

func TestRepository_Insert_PersistsTheShareReadModelAtVersionOne(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := sharemongo.NewRepository(db)

	input := usecase.RecordShareInput{
		ShareID:   uuid.New(),
		PostID:    uuid.New(),
		UserID:    uuid.New(),
		CreatedAt: time.Now().UTC(),
	}

	if err := repository.Insert(ctx, input); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	got := findShare(t, ctx, db, input.ShareID)
	if got.PostID != input.PostID.String() {
		t.Fatalf("stored document PostID = %q, want %q", got.PostID, input.PostID.String())
	}
	if got.UserID != input.UserID.String() {
		t.Fatalf("stored document UserID = %q, want %q", got.UserID, input.UserID.String())
	}
	if got.Version != 1 {
		t.Fatalf("stored document Version = %d, want 1", got.Version)
	}
}

// TestRepository_Insert_IsIdempotentOnARetriedInsertOfTheSameShareID proves
// the crash-and-retry race named by Insert's doc comment: a unique index on
// share_id makes a second Insert of the same business id a no-op (duplicate
// key treated as already-applied) rather than a second document or an
// error, so EnsureIndexes must run first.
func TestRepository_Insert_IsIdempotentOnARetriedInsertOfTheSameShareID(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := sharemongo.NewRepository(db)
	if err := repository.EnsureIndexes(ctx); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}

	shareID := uuid.New()
	input := usecase.RecordShareInput{ShareID: shareID, PostID: uuid.New(), UserID: uuid.New(), CreatedAt: time.Now().UTC()}
	if err := repository.Insert(ctx, input); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}
	if err := repository.Insert(ctx, input); err != nil {
		t.Fatalf("Insert() error = %v, want nil on a retried insert of the same share id", err)
	}

	count, err := db.Collection("post_shares").CountDocuments(ctx, bson.D{{Key: "share_id", Value: shareID.String()}})
	if err != nil {
		t.Fatalf("CountDocuments() error = %v", err)
	}
	if count != 1 {
		t.Fatalf("CountDocuments() = %d, want exactly 1 document for share_id %s", count, shareID)
	}
}

func TestRepository_Update_SucceedsAndIncrementsVersionWhenExpectedVersionMatches(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := sharemongo.NewRepository(db)

	shareID := uuid.New()
	if err := repository.Insert(ctx, usecase.RecordShareInput{ShareID: shareID, PostID: uuid.New(), UserID: uuid.New(), CreatedAt: time.Now().UTC()}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	newPostID := uuid.New()
	newUserID := uuid.New()
	update := sharemongo.UpdateInput{ShareID: shareID, ExpectedVersion: 1, PostID: newPostID, UserID: newUserID}
	if err := repository.Update(ctx, update); err != nil {
		t.Fatalf("Update() error = %v", err)
	}

	got := findShare(t, ctx, db, shareID)
	if got.PostID != newPostID.String() {
		t.Fatalf("stored document PostID = %q, want %q", got.PostID, newPostID.String())
	}
	if got.Version != 2 {
		t.Fatalf("stored document Version = %d, want 2", got.Version)
	}
}

func TestRepository_Update_ReturnsVersionConflictAndDoesNotModifyWhenExpectedVersionIsStale(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := sharemongo.NewRepository(db)

	shareID := uuid.New()
	originalPostID := uuid.New()
	if err := repository.Insert(ctx, usecase.RecordShareInput{ShareID: shareID, PostID: originalPostID, UserID: uuid.New(), CreatedAt: time.Now().UTC()}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	update := sharemongo.UpdateInput{ShareID: shareID, ExpectedVersion: 99, PostID: uuid.New(), UserID: uuid.New()}
	err := repository.Update(ctx, update)
	if !errors.Is(err, sharemongo.ErrVersionConflict) {
		t.Fatalf("Update() error = %v, want it to wrap ErrVersionConflict", err)
	}

	got := findShare(t, ctx, db, shareID)
	if got.PostID != originalPostID.String() {
		t.Fatalf("stored document PostID = %q, want unchanged %q", got.PostID, originalPostID.String())
	}
	if got.Version != 1 {
		t.Fatalf("stored document Version = %d, want unchanged 1", got.Version)
	}
}

func findShare(t *testing.T, ctx context.Context, db *mongodriver.Database, shareID uuid.UUID) shareDocument {
	t.Helper()

	var doc shareDocument
	err := db.Collection("post_shares").FindOne(ctx, bson.D{{Key: "share_id", Value: shareID.String()}}).Decode(&doc)
	if err != nil {
		t.Fatalf("FindOne() error = %v", err)
	}

	return doc
}

func TestRepository_DeleteByPostAndUser_RemovesTheMatchingShareReadModelDocument(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := sharemongo.NewRepository(db)

	postID := uuid.New()
	userID := uuid.New()
	if err := repository.Insert(ctx, usecase.RecordShareInput{ShareID: uuid.New(), PostID: postID, UserID: userID, CreatedAt: time.Now().UTC()}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	if err := repository.DeleteByPostAndUser(ctx, postID, userID); err != nil {
		t.Fatalf("DeleteByPostAndUser() error = %v", err)
	}

	count, err := db.Collection("post_shares").CountDocuments(ctx, bson.D{{Key: "post_id", Value: postID.String()}, {Key: "user_id", Value: userID.String()}})
	if err != nil {
		t.Fatalf("CountDocuments() error = %v", err)
	}
	if count != 0 {
		t.Fatalf("CountDocuments() = %d, want 0 after DeleteByPostAndUser", count)
	}
}

func TestRepository_DeleteByPostAndUser_IsANoOpWhenNoShareMatches(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := sharemongo.NewRepository(db)

	if err := repository.DeleteByPostAndUser(ctx, uuid.New(), uuid.New()); err != nil {
		t.Fatalf("DeleteByPostAndUser() error = %v, want nil for an absent document", err)
	}
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

	return client.Database("post_query_share_repository_test")
}
