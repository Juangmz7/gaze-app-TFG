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

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/application/usecase"
	collabmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/infrastructure/mongo"
)

type collabDocument struct {
	CollabID    string `bson:"collab_id"`
	PostID      string `bson:"post_id"`
	OwnerUserID string `bson:"owner_user_id"`
	Version     int64  `bson:"version"`
}

func TestRepository_Insert_PersistsTheCollabReadModelAtVersionOne(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := collabmongo.NewRepository(db)

	input := usecase.Input{
		CollabID:    uuid.New(),
		PostID:      uuid.New(),
		OwnerUserID: uuid.New(),
		CreatedAt:   time.Now().UTC(),
	}

	if err := repository.Insert(ctx, input); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	got := findCollab(t, ctx, db, input.CollabID)
	if got.PostID != input.PostID.String() {
		t.Fatalf("stored document PostID = %q, want %q", got.PostID, input.PostID.String())
	}
	if got.OwnerUserID != input.OwnerUserID.String() {
		t.Fatalf("stored document OwnerUserID = %q, want %q", got.OwnerUserID, input.OwnerUserID.String())
	}
	if got.Version != 1 {
		t.Fatalf("stored document Version = %d, want 1", got.Version)
	}
}

// TestRepository_Insert_IsIdempotentOnARetriedInsertOfTheSameCollabID
// proves the crash-and-retry race named by Insert's doc comment: a unique
// index on collab_id makes a second Insert of the same business id a no-op
// (duplicate key treated as already-applied) rather than a second document
// or an error, so EnsureIndexes must run first.
func TestRepository_Insert_IsIdempotentOnARetriedInsertOfTheSameCollabID(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := collabmongo.NewRepository(db)
	if err := repository.EnsureIndexes(ctx); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}

	collabID := uuid.New()
	input := usecase.Input{CollabID: collabID, PostID: uuid.New(), OwnerUserID: uuid.New(), CreatedAt: time.Now().UTC()}
	if err := repository.Insert(ctx, input); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}
	if err := repository.Insert(ctx, input); err != nil {
		t.Fatalf("Insert() error = %v, want nil on a retried insert of the same collab id", err)
	}

	count, err := db.Collection("post_collabs").CountDocuments(ctx, bson.D{{Key: "collab_id", Value: collabID.String()}})
	if err != nil {
		t.Fatalf("CountDocuments() error = %v", err)
	}
	if count != 1 {
		t.Fatalf("CountDocuments() = %d, want exactly 1 document for collab_id %s", count, collabID)
	}
}

func TestRepository_Update_SucceedsAndIncrementsVersionWhenExpectedVersionMatches(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := collabmongo.NewRepository(db)

	collabID := uuid.New()
	if err := repository.Insert(ctx, usecase.Input{CollabID: collabID, PostID: uuid.New(), OwnerUserID: uuid.New(), CreatedAt: time.Now().UTC()}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	newPostID := uuid.New()
	newOwnerUserID := uuid.New()
	update := collabmongo.UpdateInput{CollabID: collabID, ExpectedVersion: 1, PostID: newPostID, OwnerUserID: newOwnerUserID}
	if err := repository.Update(ctx, update); err != nil {
		t.Fatalf("Update() error = %v", err)
	}

	got := findCollab(t, ctx, db, collabID)
	if got.OwnerUserID != newOwnerUserID.String() {
		t.Fatalf("stored document OwnerUserID = %q, want %q", got.OwnerUserID, newOwnerUserID.String())
	}
	if got.Version != 2 {
		t.Fatalf("stored document Version = %d, want 2", got.Version)
	}
}

func TestRepository_Update_ReturnsVersionConflictAndDoesNotModifyWhenExpectedVersionIsStale(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := collabmongo.NewRepository(db)

	collabID := uuid.New()
	originalOwnerUserID := uuid.New()
	if err := repository.Insert(ctx, usecase.Input{CollabID: collabID, PostID: uuid.New(), OwnerUserID: originalOwnerUserID, CreatedAt: time.Now().UTC()}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	update := collabmongo.UpdateInput{CollabID: collabID, ExpectedVersion: 99, PostID: uuid.New(), OwnerUserID: uuid.New()}
	err := repository.Update(ctx, update)
	if !errors.Is(err, collabmongo.ErrVersionConflict) {
		t.Fatalf("Update() error = %v, want it to wrap ErrVersionConflict", err)
	}

	got := findCollab(t, ctx, db, collabID)
	if got.OwnerUserID != originalOwnerUserID.String() {
		t.Fatalf("stored document OwnerUserID = %q, want unchanged %q", got.OwnerUserID, originalOwnerUserID.String())
	}
	if got.Version != 1 {
		t.Fatalf("stored document Version = %d, want unchanged 1", got.Version)
	}
}

func findCollab(t *testing.T, ctx context.Context, db *mongodriver.Database, collabID uuid.UUID) collabDocument {
	t.Helper()

	var doc collabDocument
	err := db.Collection("post_collabs").FindOne(ctx, bson.D{{Key: "collab_id", Value: collabID.String()}}).Decode(&doc)
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

	return client.Database("post_query_collab_repository_test")
}
