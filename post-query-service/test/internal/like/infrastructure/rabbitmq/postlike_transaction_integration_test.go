package rabbitmq_test

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"testing"
	"time"

	"github.com/ThreeDotsLabs/watermill/message"
	"github.com/google/uuid"
	"github.com/testcontainers/testcontainers-go/modules/mongodb"
	"go.mongodb.org/mongo-driver/v2/bson"
	mongodriver "go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/like/application/usecase"
	likemongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/like/infrastructure/mongo"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/like/infrastructure/rabbitmq"
	postusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/post/application/usecase"
	postmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/post/infrastructure/mongo"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/database"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/idempotency"
)

// failingMarkRepository fails MarkProcessed after the projection already wrote.
type failingMarkRepository struct {
	*idempotency.Repository
}

func (failingMarkRepository) MarkProcessed(context.Context, uuid.UUID, uuid.UUID, string) error {
	return errors.New("crash before the idempotency record")
}

func TestPostLikeCreatedHandler_ConcurrentDuplicateDeliveriesCountOnce(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()

	db := startTransactionalMongo(t, ctx)
	handler, postID := newRealPostLikeCreatedHandler(t, ctx, db, nil)
	payload := []byte(fmt.Sprintf(`{"id":"%s","correlationId":"%s","occurredAt":"2026-03-01T10:15:30Z","postId":"%s","userId":"%s","source":"HOME_FEED","feedPosition":1,"createdAt":"2026-03-01T10:15:30Z"}`,
		uuid.NewString(), uuid.NewString(), postID, uuid.NewString()))

	// Each goroutine retries like the retry middleware does: a concurrent writer may
	// surface as a retryable error, and the redelivery must then count nothing.
	var group sync.WaitGroup
	failures := make(chan error, 8)
	for range 8 {
		group.Add(1)
		go func() {
			defer group.Done()
			var err error
			for range 5 {
				if err = handler.Handle(ctx, message.NewMessage(uuid.NewString(), payload)); err == nil {
					return
				}
			}
			failures <- err
		}()
	}
	group.Wait()
	close(failures)
	for err := range failures {
		t.Fatalf("Handle() error after retries = %v", err)
	}

	if got := storedLikeCount(t, ctx, db, postID); got != 1 {
		t.Fatalf("like_count = %d, want 1 for one event delivered concurrently", got)
	}
	likes, err := db.Collection("post_likes").CountDocuments(ctx, bson.D{{Key: "post_id", Value: postID.String()}})
	if err != nil {
		t.Fatalf("CountDocuments() error = %v", err)
	}
	if likes != 1 {
		t.Fatalf("post_likes documents = %d, want 1", likes)
	}
}

func TestPostLikeCreatedHandler_AFailureBeforeMarkProcessedRollsBackTheLikeAndTheCounter(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()

	db := startTransactionalMongo(t, ctx)
	repository := idempotency.NewRepository(db)
	handler, postID := newRealPostLikeCreatedHandler(t, ctx, db, failingMarkRepository{repository})
	payload := []byte(fmt.Sprintf(`{"id":"%s","correlationId":"%s","occurredAt":"2026-03-01T10:15:30Z","postId":"%s","userId":"%s","source":"HOME_FEED","feedPosition":1,"createdAt":"2026-03-01T10:15:30Z"}`,
		uuid.NewString(), uuid.NewString(), postID, uuid.NewString()))

	if err := handler.Handle(ctx, message.NewMessage(uuid.NewString(), payload)); err == nil {
		t.Fatal("Handle() error = nil, want the failure of MarkProcessed")
	}

	if got := storedLikeCount(t, ctx, db, postID); got != 0 {
		t.Fatalf("like_count = %d, want 0 after the rollback", got)
	}
	likes, err := db.Collection("post_likes").CountDocuments(ctx, bson.D{{Key: "post_id", Value: postID.String()}})
	if err != nil {
		t.Fatalf("CountDocuments() error = %v", err)
	}
	if likes != 0 {
		t.Fatalf("post_likes documents = %d, want 0 after the rollback", likes)
	}
}

func newRealPostLikeCreatedHandler(t *testing.T, ctx context.Context, db *mongodriver.Database, override rabbitmq.PostLikeCreatedIdempotencyRepository) (*rabbitmq.PostLikeCreatedHandler, uuid.UUID) {
	t.Helper()

	idempotencyRepository := idempotency.NewRepository(db)
	likeRepository := likemongo.NewRepository(db)
	postRepository := postmongo.NewRepository(db)
	for _, ensure := range []func(context.Context) error{idempotencyRepository.EnsureIndexes, likeRepository.EnsureIndexes, postRepository.EnsureIndexes} {
		if err := ensure(ctx); err != nil {
			t.Fatalf("EnsureIndexes() error = %v", err)
		}
	}

	postID := uuid.New()
	if err := postRepository.Insert(ctx, postusecase.CreatePostInput{PostID: postID, UserID: uuid.New(), PostType: "BASIC"}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	var marks rabbitmq.PostLikeCreatedIdempotencyRepository = idempotencyRepository
	if override != nil {
		marks = override
	}

	return rabbitmq.NewPostLikeCreatedHandler(
		database.NewTransactor(db.Client()),
		marks,
		usecase.NewRecordLike(likeRepository, postRepository),
		testPostLikeCreatedLogger(),
	), postID
}

func storedLikeCount(t *testing.T, ctx context.Context, db *mongodriver.Database, postID uuid.UUID) int64 {
	t.Helper()

	var doc struct {
		Stats struct {
			LikeCount int64 `bson:"like_count"`
		} `bson:"stats"`
	}
	if err := db.Collection("posts").FindOne(ctx, bson.D{{Key: "post_id", Value: postID.String()}}).Decode(&doc); err != nil {
		t.Fatalf("FindOne() error = %v", err)
	}

	return doc.Stats.LikeCount
}

// startTransactionalMongo starts a single-node replica set, which transactions need.
func startTransactionalMongo(t *testing.T, ctx context.Context) *mongodriver.Database {
	t.Helper()

	container, err := mongodb.Run(ctx, "mongo:7", mongodb.WithReplicaSet("rs0"))
	if err != nil {
		t.Fatalf("mongodb.Run() error = %v", err)
	}
	t.Cleanup(func() {
		if terminateErr := container.Terminate(context.Background()); terminateErr != nil {
			t.Logf("container.Terminate() error = %v", terminateErr)
		}
	})

	endpoint, err := container.PortEndpoint(ctx, "27017/tcp", "")
	if err != nil {
		t.Fatalf("container.PortEndpoint() error = %v", err)
	}

	client, err := mongodriver.Connect(options.Client().ApplyURI("mongodb://" + endpoint + "/?directConnection=true"))
	if err != nil {
		t.Fatalf("mongo.Connect() error = %v", err)
	}
	t.Cleanup(func() {
		if disconnectErr := client.Disconnect(context.Background()); disconnectErr != nil {
			t.Logf("client.Disconnect() error = %v", disconnectErr)
		}
	})

	return client.Database("post_query_like_transaction_test")
}
