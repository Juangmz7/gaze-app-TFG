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

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/comment/application/usecase"
	commentmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/comment/infrastructure/mongo"
)

type commentDocument struct {
	CommentID string `bson:"comment_id"`
	PostID    string `bson:"post_id"`
	UserID    string `bson:"user_id"`
	Content   string `bson:"content"`
}

func TestRepository_Upsert_PersistsTheCommentReadModel(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := commentmongo.NewRepository(db)

	input := usecase.Input{
		CommentID: uuid.New(),
		PostID:    uuid.New(),
		UserID:    uuid.New(),
		Content:   "nice post",
		CreatedAt: time.Now().UTC(),
	}

	if err := repository.Upsert(ctx, input); err != nil {
		t.Fatalf("Upsert() error = %v", err)
	}

	var got commentDocument
	err := db.Collection("post_comments").FindOne(ctx, bson.D{{Key: "comment_id", Value: input.CommentID.String()}}).Decode(&got)
	if err != nil {
		t.Fatalf("FindOne() error = %v", err)
	}
	if got.Content != input.Content {
		t.Fatalf("stored document Content = %q, want %q", got.Content, input.Content)
	}
	if got.PostID != input.PostID.String() {
		t.Fatalf("stored document PostID = %q, want %q", got.PostID, input.PostID.String())
	}
}

func TestRepository_Upsert_ReplacesTheExistingDocumentInsteadOfDuplicatingIt(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := commentmongo.NewRepository(db)

	commentID := uuid.New()
	input := usecase.Input{CommentID: commentID, PostID: uuid.New(), UserID: uuid.New(), Content: "first"}
	if err := repository.Upsert(ctx, input); err != nil {
		t.Fatalf("Upsert() error = %v", err)
	}
	input.Content = "edited"
	if err := repository.Upsert(ctx, input); err != nil {
		t.Fatalf("Upsert() error = %v", err)
	}

	count, err := db.Collection("post_comments").CountDocuments(ctx, bson.D{{Key: "comment_id", Value: commentID.String()}})
	if err != nil {
		t.Fatalf("CountDocuments() error = %v", err)
	}
	if count != 1 {
		t.Fatalf("CountDocuments() = %d, want exactly 1 document for comment_id %s", count, commentID)
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

	return client.Database("post_query_comment_repository_test")
}
