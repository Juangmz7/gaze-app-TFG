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

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/like/application/usecase"
	likemongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/like/infrastructure/mongo"
)

const likesCollection = "post_likes"

type likeDocument struct {
	PostID  string `bson:"post_id"`
	UserID  string `bson:"user_id"`
	Version int64  `bson:"version"`
}

func TestRepository_Insert_PersistsTheLikeReadModelAtVersionOne(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := likemongo.NewRepository(db)

	input := usecase.RecordLikeInput{PostID: uuid.New(), UserID: uuid.New(), CreatedAt: time.Now().UTC()}

	inserted, err := repository.Insert(ctx, input)
	if err != nil {
		t.Fatalf("Insert() error = %v", err)
	}
	if !inserted {
		t.Fatal("Insert() inserted = false, want true for a new document")
	}

	var got likeDocument
	filter := bson.D{{Key: "post_id", Value: input.PostID.String()}, {Key: "user_id", Value: input.UserID.String()}}
	if err := db.Collection(likesCollection).FindOne(ctx, filter).Decode(&got); err != nil {
		t.Fatalf("FindOne() error = %v", err)
	}
	if got.Version != 1 {
		t.Fatalf("stored document Version = %d, want 1", got.Version)
	}
}

func TestRepository_Insert_ReportsFalseAndWritesNothingWhenThePairAlreadyExists(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := likemongo.NewRepository(db)
	if err := repository.EnsureIndexes(ctx); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}

	input := usecase.RecordLikeInput{PostID: uuid.New(), UserID: uuid.New(), CreatedAt: time.Now().UTC()}
	if _, err := repository.Insert(ctx, input); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	inserted, err := repository.Insert(ctx, input)
	if err != nil {
		t.Fatalf("Insert() error = %v, want nil on a repeated (post, user) pair", err)
	}
	if inserted {
		t.Fatal("Insert() inserted = true, want false on a repeated (post, user) pair")
	}

	count, err := db.Collection(likesCollection).CountDocuments(ctx, bson.D{{Key: "post_id", Value: input.PostID.String()}})
	if err != nil {
		t.Fatalf("CountDocuments() error = %v", err)
	}
	if count != 1 {
		t.Fatalf("CountDocuments() = %d, want exactly 1", count)
	}
}

func TestRepository_Insert_AllowsSeveralUsersOnOnePostAfterEnsureIndexes(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := likemongo.NewRepository(db)
	if err := repository.EnsureIndexes(ctx); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}

	postID := uuid.New()
	for range 3 {
		inserted, err := repository.Insert(ctx, usecase.RecordLikeInput{PostID: postID, UserID: uuid.New(), CreatedAt: time.Now().UTC()})
		if err != nil {
			t.Fatalf("Insert() error = %v", err)
		}
		if !inserted {
			t.Fatal("Insert() inserted = false, want true for a different user")
		}
	}
}

// Earlier versions created a unique index on the removed like_id field. Left in
// place it would index every new document as null and reject all but the first.
func TestRepository_EnsureIndexes_DropsTheLegacyIDIndexAndIsRepeatable(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	collection := db.Collection(likesCollection)
	if _, err := collection.Indexes().CreateOne(ctx, mongodriver.IndexModel{
		Keys:    bson.D{{Key: "like_id", Value: 1}},
		Options: options.Index().SetUnique(true),
	}); err != nil {
		t.Fatalf("CreateOne() error = %v", err)
	}

	repository := likemongo.NewRepository(db)
	for range 2 {
		if err := repository.EnsureIndexes(ctx); err != nil {
			t.Fatalf("EnsureIndexes() error = %v", err)
		}
	}

	postID := uuid.New()
	for range 2 {
		if inserted, err := repository.Insert(ctx, usecase.RecordLikeInput{PostID: postID, UserID: uuid.New(), CreatedAt: time.Now().UTC()}); err != nil || !inserted {
			t.Fatalf("Insert() inserted/error = %v/%v, want true/nil once the legacy index is gone", inserted, err)
		}
	}
}

func TestRepository_DeleteByPostAndUser_RemovesTheDocumentAndReportsIt(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := likemongo.NewRepository(db)

	postID := uuid.New()
	userID := uuid.New()
	if _, err := repository.Insert(ctx, usecase.RecordLikeInput{PostID: postID, UserID: userID, CreatedAt: time.Now().UTC()}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	removed, err := repository.DeleteByPostAndUser(ctx, postID, userID)
	if err != nil {
		t.Fatalf("DeleteByPostAndUser() error = %v", err)
	}
	if !removed {
		t.Fatal("DeleteByPostAndUser() removed = false, want true")
	}

	count, err := db.Collection(likesCollection).CountDocuments(ctx, bson.D{{Key: "post_id", Value: postID.String()}, {Key: "user_id", Value: userID.String()}})
	if err != nil {
		t.Fatalf("CountDocuments() error = %v", err)
	}
	if count != 0 {
		t.Fatalf("CountDocuments() = %d, want 0 after DeleteByPostAndUser", count)
	}
}

func TestRepository_DeleteByPostAndUser_ReportsFalseWhenNothingMatches(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	repository := likemongo.NewRepository(testDatabase(t, ctx))

	removed, err := repository.DeleteByPostAndUser(ctx, uuid.New(), uuid.New())
	if err != nil {
		t.Fatalf("DeleteByPostAndUser() error = %v, want nil for an absent document", err)
	}
	if removed {
		t.Fatal("DeleteByPostAndUser() removed = true, want false for an absent document")
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

	return client.Database("post_query_like_repository_test")
}
