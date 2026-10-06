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

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/like/application/usecase"
	likemongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/like/infrastructure/mongo"
)

type likeDocument struct {
	LikeID  string `bson:"like_id"`
	PostID  string `bson:"post_id"`
	UserID  string `bson:"user_id"`
	Version int64  `bson:"version"`
}

func TestRepository_Insert_PersistsTheLikeReadModelAtVersionOne(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := likemongo.NewRepository(db)

	input := usecase.Input{
		LikeID:    uuid.New(),
		PostID:    uuid.New(),
		UserID:    uuid.New(),
		CreatedAt: time.Now().UTC(),
	}

	if err := repository.Insert(ctx, input); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	got := findLike(t, ctx, db, input.LikeID)
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

// TestRepository_Insert_IsIdempotentOnARetriedInsertOfTheSameLikeID proves
// the crash-and-retry race named by Insert's doc comment: a unique index on
// like_id makes a second Insert of the same business id a no-op (duplicate
// key treated as already-applied) rather than a second document or an
// error, so EnsureIndexes must run first.
func TestRepository_Insert_IsIdempotentOnARetriedInsertOfTheSameLikeID(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := likemongo.NewRepository(db)
	if err := repository.EnsureIndexes(ctx); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}

	likeID := uuid.New()
	input := usecase.Input{LikeID: likeID, PostID: uuid.New(), UserID: uuid.New(), CreatedAt: time.Now().UTC()}
	if err := repository.Insert(ctx, input); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}
	if err := repository.Insert(ctx, input); err != nil {
		t.Fatalf("Insert() error = %v, want nil on a retried insert of the same like id", err)
	}

	count, err := db.Collection("post_likes").CountDocuments(ctx, bson.D{{Key: "like_id", Value: likeID.String()}})
	if err != nil {
		t.Fatalf("CountDocuments() error = %v", err)
	}
	if count != 1 {
		t.Fatalf("CountDocuments() = %d, want exactly 1 document for like_id %s", count, likeID)
	}
}

func TestRepository_Update_SucceedsAndIncrementsVersionWhenExpectedVersionMatches(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := likemongo.NewRepository(db)

	likeID := uuid.New()
	if err := repository.Insert(ctx, usecase.Input{LikeID: likeID, PostID: uuid.New(), UserID: uuid.New(), CreatedAt: time.Now().UTC()}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	newPostID := uuid.New()
	newUserID := uuid.New()
	update := likemongo.UpdateInput{LikeID: likeID, ExpectedVersion: 1, PostID: newPostID, UserID: newUserID}
	if err := repository.Update(ctx, update); err != nil {
		t.Fatalf("Update() error = %v", err)
	}

	got := findLike(t, ctx, db, likeID)
	if got.PostID != newPostID.String() {
		t.Fatalf("stored document PostID = %q, want %q", got.PostID, newPostID.String())
	}
	if got.UserID != newUserID.String() {
		t.Fatalf("stored document UserID = %q, want %q", got.UserID, newUserID.String())
	}
	if got.Version != 2 {
		t.Fatalf("stored document Version = %d, want 2", got.Version)
	}
}

func TestRepository_Update_ReturnsVersionConflictAndDoesNotModifyWhenExpectedVersionIsStale(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := likemongo.NewRepository(db)

	likeID := uuid.New()
	originalPostID := uuid.New()
	if err := repository.Insert(ctx, usecase.Input{LikeID: likeID, PostID: originalPostID, UserID: uuid.New(), CreatedAt: time.Now().UTC()}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	update := likemongo.UpdateInput{LikeID: likeID, ExpectedVersion: 99, PostID: uuid.New(), UserID: uuid.New()}
	err := repository.Update(ctx, update)
	if !errors.Is(err, likemongo.ErrVersionConflict) {
		t.Fatalf("Update() error = %v, want it to wrap ErrVersionConflict", err)
	}

	got := findLike(t, ctx, db, likeID)
	if got.PostID != originalPostID.String() {
		t.Fatalf("stored document PostID = %q, want unchanged %q", got.PostID, originalPostID.String())
	}
	if got.Version != 1 {
		t.Fatalf("stored document Version = %d, want unchanged 1", got.Version)
	}
}

func findLike(t *testing.T, ctx context.Context, db *mongodriver.Database, likeID uuid.UUID) likeDocument {
	t.Helper()

	var doc likeDocument
	err := db.Collection("post_likes").FindOne(ctx, bson.D{{Key: "like_id", Value: likeID.String()}}).Decode(&doc)
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

	return client.Database("post_query_like_repository_test")
}
