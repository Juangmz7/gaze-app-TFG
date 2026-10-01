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

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/share/application/usecase/recordshare"
	sharemongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/share/infrastructure/mongo"
)

type shareDocument struct {
	ShareID string `bson:"share_id"`
	PostID  string `bson:"post_id"`
	UserID  string `bson:"user_id"`
}

func TestRepository_Upsert_PersistsTheShareReadModel(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := sharemongo.NewRepository(db)

	input := recordshare.Input{
		ShareID:   uuid.New(),
		PostID:    uuid.New(),
		UserID:    uuid.New(),
		CreatedAt: time.Now().UTC(),
	}

	if err := repository.Upsert(ctx, input); err != nil {
		t.Fatalf("Upsert() error = %v", err)
	}

	var got shareDocument
	err := db.Collection("post_shares").FindOne(ctx, bson.D{{Key: "share_id", Value: input.ShareID.String()}}).Decode(&got)
	if err != nil {
		t.Fatalf("FindOne() error = %v", err)
	}
	if got.PostID != input.PostID.String() {
		t.Fatalf("stored document PostID = %q, want %q", got.PostID, input.PostID.String())
	}
	if got.UserID != input.UserID.String() {
		t.Fatalf("stored document UserID = %q, want %q", got.UserID, input.UserID.String())
	}
}

func TestRepository_Upsert_ReplacesTheExistingDocumentInsteadOfDuplicatingIt(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := sharemongo.NewRepository(db)

	shareID := uuid.New()
	input := recordshare.Input{ShareID: shareID, PostID: uuid.New(), UserID: uuid.New()}
	if err := repository.Upsert(ctx, input); err != nil {
		t.Fatalf("Upsert() error = %v", err)
	}
	if err := repository.Upsert(ctx, input); err != nil {
		t.Fatalf("Upsert() error = %v", err)
	}

	count, err := db.Collection("post_shares").CountDocuments(ctx, bson.D{{Key: "share_id", Value: shareID.String()}})
	if err != nil {
		t.Fatalf("CountDocuments() error = %v", err)
	}
	if count != 1 {
		t.Fatalf("CountDocuments() = %d, want exactly 1 document for share_id %s", count, shareID)
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
