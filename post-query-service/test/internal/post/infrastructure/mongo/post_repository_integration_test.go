package mongo_test

import (
	"context"
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
	PostID      string   `bson:"post_id"`
	UserID      string   `bson:"user_id"`
	PostType    string   `bson:"post_type"`
	Description string   `bson:"description"`
	Tags        []string `bson:"tags"`
}

// TestRepository_Upsert_PersistsThePostReadModel proves the repository
// actually writes a retrievable document to MongoDB with the expected
// fields.
func TestRepository_Upsert_PersistsThePostReadModel(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := postmongo.NewRepository(db)

	input := usecase.Input{
		PostID:      uuid.New(),
		UserID:      uuid.New(),
		PostType:    "TEXT",
		Description: "a distributed systems post",
		Tags:        []string{"go", "rabbitmq"},
		CreatedAt:   time.Now().UTC(),
		UpdatedAt:   time.Now().UTC(),
	}

	if err := repository.Upsert(ctx, input); err != nil {
		t.Fatalf("Upsert() error = %v", err)
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
}

// TestRepository_Upsert_ReplacesTheExistingDocumentInsteadOfDuplicatingIt
// proves repeated projections of the same post_id converge on a single
// document, matching the idempotent-projection contract relied on by
// postcreated.Handler.
func TestRepository_Upsert_ReplacesTheExistingDocumentInsteadOfDuplicatingIt(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := postmongo.NewRepository(db)

	postID := uuid.New()
	input := usecase.Input{PostID: postID, UserID: uuid.New(), PostType: "TEXT", Description: "first version"}
	if err := repository.Upsert(ctx, input); err != nil {
		t.Fatalf("Upsert() error = %v", err)
	}

	input.Description = "updated version"
	if err := repository.Upsert(ctx, input); err != nil {
		t.Fatalf("Upsert() error = %v", err)
	}

	count, err := db.Collection("posts").CountDocuments(ctx, bson.D{{Key: "post_id", Value: postID.String()}})
	if err != nil {
		t.Fatalf("CountDocuments() error = %v", err)
	}
	if count != 1 {
		t.Fatalf("CountDocuments() = %d, want exactly 1 document for post_id %s", count, postID)
	}

	got := findPost(t, ctx, db, postID)
	if got.Description != "updated version" {
		t.Fatalf("stored document Description = %q, want %q", got.Description, "updated version")
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
