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

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/user/application/usecase"
	usermongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/user/infrastructure/mongo"
)

type userDocument struct {
	UserID         string            `bson:"user_id"`
	Username       string            `bson:"username"`
	Email          string            `bson:"email"`
	BioDescription string            `bson:"bio_description"`
	BioSocialMedia map[string]string `bson:"bio_social_media"`
	PictureURL     string            `bson:"picture_url"`
	AccountStatus  string            `bson:"account_status"`
	Version        int64             `bson:"version"`
}

func TestRepository_Insert_PersistsTheUserReadModelAtVersionOne(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := usermongo.NewRepository(db)

	input := usecase.RegisterUserInput{
		UserID:    uuid.New(),
		Username:  "ada-lovelace",
		CreatedAt: time.Now().UTC(),
	}

	if err := repository.Insert(ctx, input); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	got := findUser(t, ctx, db, input.UserID)
	if got.Username != input.Username {
		t.Fatalf("stored document Username = %q, want %q", got.Username, input.Username)
	}
	if got.Version != 1 {
		t.Fatalf("stored document Version = %d, want 1", got.Version)
	}
}

// TestRepository_Insert_IsIdempotentOnARetriedInsertOfTheSameUserID proves
// the crash-and-retry race named by Insert's doc comment: a unique index on
// user_id makes a second Insert of the same business id a no-op (duplicate
// key treated as already-applied) rather than a second document or an
// error, so EnsureIndexes must run first.
func TestRepository_Insert_IsIdempotentOnARetriedInsertOfTheSameUserID(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := usermongo.NewRepository(db)
	if err := repository.EnsureIndexes(ctx); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}

	userID := uuid.New()
	input := usecase.RegisterUserInput{UserID: userID, Username: "ada-lovelace", CreatedAt: time.Now().UTC()}
	if err := repository.Insert(ctx, input); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}
	if err := repository.Insert(ctx, input); err != nil {
		t.Fatalf("Insert() error = %v, want nil on a retried insert of the same user id", err)
	}

	count, err := db.Collection("users").CountDocuments(ctx, bson.D{{Key: "user_id", Value: userID.String()}})
	if err != nil {
		t.Fatalf("CountDocuments() error = %v", err)
	}
	if count != 1 {
		t.Fatalf("CountDocuments() = %d, want exactly 1 document for user_id %s", count, userID)
	}
}

func TestRepository_Update_SucceedsAndIncrementsVersionWhenExpectedVersionMatches(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := usermongo.NewRepository(db)

	userID := uuid.New()
	if err := repository.Insert(ctx, usecase.RegisterUserInput{UserID: userID, Username: "ada-lovelace", CreatedAt: time.Now().UTC()}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	update := usecase.UpdateUserInput{
		UserID:          userID,
		ExpectedVersion: 1,
		Username:        "ada-lovelace-renamed",
		Email:           "ada@example.com",
		BioDescription:  "mathematician",
		BioSocialMedia:  map[string]string{"twitter": "@ada"},
		PictureURL:      "https://example.com/ada.png",
		AccountStatus:   "ACCEPTED",
		UpdatedAt:       time.Now().UTC(),
	}
	if err := repository.Update(ctx, update); err != nil {
		t.Fatalf("Update() error = %v", err)
	}

	got := findUser(t, ctx, db, userID)
	if got.Username != "ada-lovelace-renamed" {
		t.Fatalf("stored document Username = %q, want %q", got.Username, "ada-lovelace-renamed")
	}
	if got.Email != update.Email {
		t.Fatalf("stored document Email = %q, want %q", got.Email, update.Email)
	}
	if got.AccountStatus != update.AccountStatus {
		t.Fatalf("stored document AccountStatus = %q, want %q", got.AccountStatus, update.AccountStatus)
	}
	if got.Version != 2 {
		t.Fatalf("stored document Version = %d, want 2", got.Version)
	}
}

func TestRepository_GetVersion_ReturnsTheStoredVersionWhenTheUserExists(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := usermongo.NewRepository(db)

	userID := uuid.New()
	if err := repository.Insert(ctx, usecase.RegisterUserInput{UserID: userID, Username: "ada-lovelace", CreatedAt: time.Now().UTC()}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	version, found, err := repository.GetVersion(ctx, userID)
	if err != nil {
		t.Fatalf("GetVersion() error = %v", err)
	}
	if !found {
		t.Fatal("GetVersion() found = false, want true for an existing user")
	}
	if version != 1 {
		t.Fatalf("GetVersion() version = %d, want 1", version)
	}
}

func TestRepository_GetVersion_ReturnsNotFoundWhenTheUserWasNeverProjected(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := usermongo.NewRepository(db)

	_, found, err := repository.GetVersion(ctx, uuid.New())
	if err != nil {
		t.Fatalf("GetVersion() error = %v", err)
	}
	if found {
		t.Fatal("GetVersion() found = true, want false for a user that was never projected")
	}
}

func TestRepository_Update_ReturnsVersionConflictAndDoesNotModifyWhenExpectedVersionIsStale(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := usermongo.NewRepository(db)

	userID := uuid.New()
	if err := repository.Insert(ctx, usecase.RegisterUserInput{UserID: userID, Username: "ada-lovelace", CreatedAt: time.Now().UTC()}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	update := usecase.UpdateUserInput{UserID: userID, ExpectedVersion: 99, Username: "should-not-apply"}
	err := repository.Update(ctx, update)
	if !errors.Is(err, usermongo.ErrVersionConflict) {
		t.Fatalf("Update() error = %v, want it to wrap ErrVersionConflict", err)
	}

	got := findUser(t, ctx, db, userID)
	if got.Username != "ada-lovelace" {
		t.Fatalf("stored document Username = %q, want unchanged %q", got.Username, "ada-lovelace")
	}
	if got.Version != 1 {
		t.Fatalf("stored document Version = %d, want unchanged 1", got.Version)
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
	if err := repository.Insert(ctx, usecase.RegisterUserInput{UserID: userID, Username: "ada-lovelace"}); err != nil {
		t.Fatalf("Insert() error = %v", err)
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

func findUser(t *testing.T, ctx context.Context, db *mongodriver.Database, userID uuid.UUID) userDocument {
	t.Helper()

	var doc userDocument
	err := db.Collection("users").FindOne(ctx, bson.D{{Key: "user_id", Value: userID.String()}}).Decode(&doc)
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

	return client.Database("post_query_user_repository_test")
}
