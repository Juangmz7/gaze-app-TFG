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

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/follow/application/usecase"
	followmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/follow/infrastructure/mongo"
)

type followDocument struct {
	FollowerUserID string `bson:"follower_user_id"`
	FollowedUserID string `bson:"followed_user_id"`
	Version        int64  `bson:"version"`
}

func TestRepository_Insert_PersistsTheFollowReadModelAtVersionOne(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := followmongo.NewRepository(db)

	input := usecase.CreateFollowInput{
		FollowerUserID: uuid.New(),
		FollowedUserID: uuid.New(),
		CreatedAt:      time.Now().UTC(),
	}

	if err := repository.Insert(ctx, input); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	got := findFollow(t, ctx, db, input.FollowerUserID, input.FollowedUserID)
	if got.Version != 1 {
		t.Fatalf("stored document Version = %d, want 1", got.Version)
	}
}

// TestRepository_Insert_IsIdempotentOnARetriedInsertOfTheSameFollow proves
// the crash-and-retry race named by Insert's doc comment: a unique
// compound index on (follower_user_id, followed_user_id) makes a second
// Insert of the same pair a no-op rather than a second document or an
// error, so EnsureIndexes must run first.
func TestRepository_Insert_IsIdempotentOnARetriedInsertOfTheSameFollow(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := followmongo.NewRepository(db)
	if err := repository.EnsureIndexes(ctx); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}

	input := usecase.CreateFollowInput{FollowerUserID: uuid.New(), FollowedUserID: uuid.New(), CreatedAt: time.Now().UTC()}
	if err := repository.Insert(ctx, input); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}
	if err := repository.Insert(ctx, input); err != nil {
		t.Fatalf("Insert() error = %v, want nil on a retried insert of the same follow", err)
	}

	count, err := db.Collection("user_follows").CountDocuments(ctx, bson.D{
		{Key: "follower_user_id", Value: input.FollowerUserID.String()},
		{Key: "followed_user_id", Value: input.FollowedUserID.String()},
	})
	if err != nil {
		t.Fatalf("CountDocuments() error = %v", err)
	}
	if count != 1 {
		t.Fatalf("CountDocuments() = %d, want exactly 1 document", count)
	}
}

func TestRepository_Delete_RemovesTheFollowReadModel(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := followmongo.NewRepository(db)

	followerID := uuid.New()
	followedID := uuid.New()
	if err := repository.Insert(ctx, usecase.CreateFollowInput{FollowerUserID: followerID, FollowedUserID: followedID, CreatedAt: time.Now().UTC()}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	if err := repository.Delete(ctx, followerID, followedID); err != nil {
		t.Fatalf("Delete() error = %v", err)
	}

	count, err := db.Collection("user_follows").CountDocuments(ctx, bson.D{
		{Key: "follower_user_id", Value: followerID.String()},
		{Key: "followed_user_id", Value: followedID.String()},
	})
	if err != nil {
		t.Fatalf("CountDocuments() error = %v", err)
	}
	if count != 0 {
		t.Fatalf("CountDocuments() = %d, want 0 after Delete", count)
	}
}

// TestRepository_Delete_IsANoOpWhenTheFollowWasNeverProjected proves
// deleting an absent follow read model is not an error, matching the
// handler's idempotent-deletion contract for redelivered/out-of-order
// UserUnfollowedEvent messages.
func TestRepository_Delete_IsANoOpWhenTheFollowWasNeverProjected(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := followmongo.NewRepository(db)

	if err := repository.Delete(ctx, uuid.New(), uuid.New()); err != nil {
		t.Fatalf("Delete() error = %v, want nil when the follow was never projected", err)
	}
}

func findFollow(t *testing.T, ctx context.Context, db *mongodriver.Database, followerUserID, followedUserID uuid.UUID) followDocument {
	t.Helper()

	var doc followDocument
	filter := bson.D{
		{Key: "follower_user_id", Value: followerUserID.String()},
		{Key: "followed_user_id", Value: followedUserID.String()},
	}
	if err := db.Collection("user_follows").FindOne(ctx, filter).Decode(&doc); err != nil {
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

	return client.Database("post_query_follow_repository_test")
}
