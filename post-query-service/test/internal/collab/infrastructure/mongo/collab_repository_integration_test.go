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

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/application/usecase/recordcollabopened"
	collabmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/infrastructure/mongo"
)

type collabDocument struct {
	CollabID    string `bson:"collab_id"`
	PostID      string `bson:"post_id"`
	OwnerUserID string `bson:"owner_user_id"`
}

func TestRepository_Upsert_PersistsTheCollabReadModel(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := collabmongo.NewRepository(db)

	input := recordcollabopened.Input{
		CollabID:    uuid.New(),
		PostID:      uuid.New(),
		OwnerUserID: uuid.New(),
		CreatedAt:   time.Now().UTC(),
	}

	if err := repository.Upsert(ctx, input); err != nil {
		t.Fatalf("Upsert() error = %v", err)
	}

	var got collabDocument
	err := db.Collection("post_collabs").FindOne(ctx, bson.D{{Key: "collab_id", Value: input.CollabID.String()}}).Decode(&got)
	if err != nil {
		t.Fatalf("FindOne() error = %v", err)
	}
	if got.PostID != input.PostID.String() {
		t.Fatalf("stored document PostID = %q, want %q", got.PostID, input.PostID.String())
	}
	if got.OwnerUserID != input.OwnerUserID.String() {
		t.Fatalf("stored document OwnerUserID = %q, want %q", got.OwnerUserID, input.OwnerUserID.String())
	}
}

func TestRepository_Upsert_ReplacesTheExistingDocumentInsteadOfDuplicatingIt(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := collabmongo.NewRepository(db)

	collabID := uuid.New()
	input := recordcollabopened.Input{CollabID: collabID, PostID: uuid.New(), OwnerUserID: uuid.New()}
	if err := repository.Upsert(ctx, input); err != nil {
		t.Fatalf("Upsert() error = %v", err)
	}
	if err := repository.Upsert(ctx, input); err != nil {
		t.Fatalf("Upsert() error = %v", err)
	}

	count, err := db.Collection("post_collabs").CountDocuments(ctx, bson.D{{Key: "collab_id", Value: collabID.String()}})
	if err != nil {
		t.Fatalf("CountDocuments() error = %v", err)
	}
	if count != 1 {
		t.Fatalf("CountDocuments() = %d, want exactly 1 document for collab_id %s", count, collabID)
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

	return client.Database("post_query_collab_repository_test")
}
