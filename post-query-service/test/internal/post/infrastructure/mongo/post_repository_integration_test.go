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

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/post/application/usecase"
	postmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/post/infrastructure/mongo"
)

// postDocument mirrors the persisted shape written by postmongo.Repository,
// used here only to decode what was actually written to MongoDB.
type postDocument struct {
	PostID      string    `bson:"post_id"`
	UserID      string    `bson:"user_id"`
	PostType    string    `bson:"post_type"`
	Description string    `bson:"description"`
	Tags        []string  `bson:"tags"`
	UpdatedAt   time.Time `bson:"updated_at"`
	Version     int64     `bson:"version"`
}

// TestRepository_Insert_PersistsThePostReadModelAtVersionOne proves the
// repository actually writes a retrievable document to MongoDB with the
// expected fields and starts it at version 1.
func TestRepository_Insert_PersistsThePostReadModelAtVersionOne(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := postmongo.NewRepository(db)

	input := usecase.CreatePostInput{
		PostID:      uuid.New(),
		UserID:      uuid.New(),
		PostType:    "TEXT",
		Description: "a distributed systems post",
		Tags:        []string{"go", "rabbitmq"},
		CreatedAt:   time.Now().UTC(),
		UpdatedAt:   time.Now().UTC(),
	}

	if err := repository.Insert(ctx, input); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	got := findPost(t, ctx, db, input.PostID)
	if got.UserID != input.UserID.String() {
		t.Fatalf("stored document UserID = %q, want %q", got.UserID, input.UserID.String())
	}
	if got.PostType != input.PostType {
		t.Fatalf("stored document PostType = %q, want %q", got.PostType, input.PostType)
	}
	if got.Description != input.Description {
		t.Fatalf("stored document Description = %q, want %q", got.Description, input.Description)
	}
	if len(got.Tags) != 2 || got.Tags[0] != "go" || got.Tags[1] != "rabbitmq" {
		t.Fatalf("stored document Tags = %v, want [go rabbitmq]", got.Tags)
	}
	if got.Version != 1 {
		t.Fatalf("stored document Version = %d, want 1", got.Version)
	}
}

// TestRepository_Insert_IsIdempotentOnARetriedInsertOfTheSamePostID proves
// the crash-and-retry race named by Insert's doc comment: a unique index on
// post_id makes a second Insert of the same business id a no-op (duplicate
// key treated as already-applied) rather than a second document or an
// error, so EnsureIndexes must run first.
func TestRepository_Insert_IsIdempotentOnARetriedInsertOfTheSamePostID(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := postmongo.NewRepository(db)
	if err := repository.EnsureIndexes(ctx); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}

	postID := uuid.New()
	input := usecase.CreatePostInput{PostID: postID, UserID: uuid.New(), PostType: "TEXT", Description: "first version"}
	if err := repository.Insert(ctx, input); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}
	if err := repository.Insert(ctx, input); err != nil {
		t.Fatalf("Insert() error = %v, want nil on a retried insert of the same post id", err)
	}

	count, err := db.Collection("posts").CountDocuments(ctx, bson.D{{Key: "post_id", Value: postID.String()}})
	if err != nil {
		t.Fatalf("CountDocuments() error = %v", err)
	}
	if count != 1 {
		t.Fatalf("CountDocuments() = %d, want exactly 1 document for post_id %s", count, postID)
	}
}

// TestRepository_Update_SucceedsAndIncrementsVersionWhenExpectedVersionMatches
// proves Update applies the optimistic-concurrency contract: it only
// succeeds when the stored version matches, and bumps the version on
// success.
func TestRepository_Update_SucceedsAndIncrementsVersionWhenExpectedVersionMatches(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := postmongo.NewRepository(db)

	postID := uuid.New()
	if err := repository.Insert(ctx, usecase.CreatePostInput{PostID: postID, UserID: uuid.New(), PostType: "TEXT", Description: "first version"}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	update := usecase.UpdatePostInput{
		PostID:          postID,
		ExpectedVersion: 1,
		PostType:        "TEXT",
		Description:     "updated version",
		Tags:            []string{"edited"},
		UpdatedAt:       time.Now().UTC(),
	}
	if err := repository.Update(ctx, update); err != nil {
		t.Fatalf("Update() error = %v", err)
	}

	got := findPost(t, ctx, db, postID)
	if got.Description != "updated version" {
		t.Fatalf("stored document Description = %q, want %q", got.Description, "updated version")
	}
	if len(got.Tags) != 1 || got.Tags[0] != "edited" {
		t.Fatalf("stored document Tags = %v, want [edited]", got.Tags)
	}
	if got.Version != 2 {
		t.Fatalf("stored document Version = %d, want 2", got.Version)
	}
}

// TestRepository_Update_ReturnsVersionConflictAndDoesNotModifyWhenExpectedVersionIsStale
// proves a stale ExpectedVersion neither modifies the document nor silently
// succeeds; it returns ErrVersionConflict.
func TestRepository_Update_ReturnsVersionConflictAndDoesNotModifyWhenExpectedVersionIsStale(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := postmongo.NewRepository(db)

	postID := uuid.New()
	if err := repository.Insert(ctx, usecase.CreatePostInput{PostID: postID, UserID: uuid.New(), PostType: "TEXT", Description: "first version"}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	update := usecase.UpdatePostInput{PostID: postID, ExpectedVersion: 99, PostType: "TEXT", Description: "should not apply"}
	err := repository.Update(ctx, update)
	if !errors.Is(err, postmongo.ErrVersionConflict) {
		t.Fatalf("Update() error = %v, want it to wrap ErrVersionConflict", err)
	}

	got := findPost(t, ctx, db, postID)
	if got.Description != "first version" {
		t.Fatalf("stored document Description = %q, want unchanged %q", got.Description, "first version")
	}
	if got.Version != 1 {
		t.Fatalf("stored document Version = %d, want unchanged 1", got.Version)
	}
}

func TestRepository_GetVersion_ReturnsTheStoredVersionWhenThePostExists(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := postmongo.NewRepository(db)

	postID := uuid.New()
	if err := repository.Insert(ctx, usecase.CreatePostInput{PostID: postID, UserID: uuid.New(), PostType: "TEXT", Description: "first version"}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	version, found, err := repository.GetVersion(ctx, postID)
	if err != nil {
		t.Fatalf("GetVersion() error = %v", err)
	}
	if !found {
		t.Fatal("GetVersion() found = false, want true for an existing post")
	}
	if version != 1 {
		t.Fatalf("GetVersion() version = %d, want 1", version)
	}
}

func TestRepository_GetVersion_ReturnsNotFoundWhenThePostWasNeverProjected(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := postmongo.NewRepository(db)

	_, found, err := repository.GetVersion(ctx, uuid.New())
	if err != nil {
		t.Fatalf("GetVersion() error = %v", err)
	}
	if found {
		t.Fatal("GetVersion() found = true, want false for a post that was never projected")
	}
}

func TestRepository_Delete_RemovesAnExistingPostReadModelDocument(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := postmongo.NewRepository(db)

	postID := uuid.New()
	if err := repository.Insert(ctx, usecase.CreatePostInput{PostID: postID, UserID: uuid.New(), PostType: "TEXT", Description: "first version"}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	if err := repository.Delete(ctx, postID); err != nil {
		t.Fatalf("Delete() error = %v", err)
	}

	count, err := db.Collection("posts").CountDocuments(ctx, bson.D{{Key: "post_id", Value: postID.String()}})
	if err != nil {
		t.Fatalf("CountDocuments() error = %v", err)
	}
	if count != 0 {
		t.Fatalf("CountDocuments() = %d, want 0 after Delete", count)
	}
}

func TestRepository_Delete_IsANoOpWhenThePostWasNeverProjected(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := postmongo.NewRepository(db)

	if err := repository.Delete(ctx, uuid.New()); err != nil {
		t.Fatalf("Delete() error = %v, want nil for an absent document", err)
	}
}

func findPost(t *testing.T, ctx context.Context, db *mongodriver.Database, postID uuid.UUID) postDocument {
	t.Helper()

	var doc postDocument
	err := db.Collection("posts").FindOne(ctx, bson.D{{Key: "post_id", Value: postID.String()}}).Decode(&doc)
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

	return client.Database("post_query_post_repository_test")
}
