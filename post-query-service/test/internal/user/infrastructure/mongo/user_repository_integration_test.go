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

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/user/application/usecase"
	usermongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/user/infrastructure/mongo"
)

type userDocument struct {
	UserID   string `bson:"user_id"`
	Username string `bson:"username"`
}

func TestRepository_Upsert_PersistsTheUserReadModel(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := usermongo.NewRepository(db)

	input := usecase.RegisterUserInput{
		UserID:    uuid.New(),
		Username:  "ada-lovelace",
		CreatedAt: time.Now().UTC(),
	}

	if err := repository.Upsert(ctx, input); err != nil {
		t.Fatalf("Upsert() error = %v", err)
	}

	var got userDocument
	err := db.Collection("users").FindOne(ctx, bson.D{{Key: "user_id", Value: input.UserID.String()}}).Decode(&got)
	if err != nil {
		t.Fatalf("FindOne() error = %v", err)
	}
	if got.Username != input.Username {
		t.Fatalf("stored document Username = %q, want %q", got.Username, input.Username)
	}
}

func TestRepository_Upsert_ReplacesTheExistingDocumentInsteadOfDuplicatingIt(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := usermongo.NewRepository(db)

	userID := uuid.New()
	input := usecase.RegisterUserInput{UserID: userID, Username: "ada-lovelace"}
	if err := repository.Upsert(ctx, input); err != nil {
		t.Fatalf("Upsert() error = %v", err)
	}
	input.Username = "ada-lovelace-renamed"
	if err := repository.Upsert(ctx, input); err != nil {
		t.Fatalf("Upsert() error = %v", err)
	}

	count, err := db.Collection("users").CountDocuments(ctx, bson.D{{Key: "user_id", Value: userID.String()}})
	if err != nil {
		t.Fatalf("CountDocuments() error = %v", err)
	}
	if count != 1 {
		t.Fatalf("CountDocuments() = %d, want exactly 1 document for user_id %s", count, userID)
	}
}

// TestRepository_Delete_RemovesTheUserReadModel proves the repository's
// usecase.DeleteUserRepository port: a previously projected user is no longer
// retrievable after Delete.
func TestRepository_Delete_RemovesTheUserReadModel(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := usermongo.NewRepository(db)

	userID := uuid.New()
	if err := repository.Upsert(ctx, usecase.RegisterUserInput{UserID: userID, Username: "ada-lovelace"}); err != nil {
		t.Fatalf("Upsert() error = %v", err)
	}

	if err := repository.Delete(ctx, userID); err != nil {
		t.Fatalf("Delete() error = %v", err)
	}

	count, err := db.Collection("users").CountDocuments(ctx, bson.D{{Key: "user_id", Value: userID.String()}})
	if err != nil {
		t.Fatalf("CountDocuments() error = %v", err)
	}
	if count != 0 {
		t.Fatalf("CountDocuments() = %d, want 0 after Delete", count)
	}
}

// TestRepository_Delete_IsANoOpWhenTheUserWasNeverProjected proves deleting
// an absent user read model is not an error, matching the handler's
// idempotent-deletion contract for redelivered/out-of-order UserDeletedEvent
// messages.
func TestRepository_Delete_IsANoOpWhenTheUserWasNeverProjected(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := usermongo.NewRepository(db)

	if err := repository.Delete(ctx, uuid.New()); err != nil {
		t.Fatalf("Delete() error = %v, want nil when the user was never projected", err)
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

	return client.Database("post_query_user_repository_test")
}
