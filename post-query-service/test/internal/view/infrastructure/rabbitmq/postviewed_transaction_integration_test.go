package rabbitmq_test

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"sync"
	"testing"
	"time"

	"github.com/ThreeDotsLabs/watermill/message"
	"github.com/google/uuid"
	"github.com/testcontainers/testcontainers-go/modules/mongodb"
	"go.mongodb.org/mongo-driver/v2/bson"
	mongodriver "go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	postusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/post/application/usecase"
	postmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/post/infrastructure/mongo"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/database"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/idempotency"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/view/application/usecase"
	viewmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/view/infrastructure/mongo"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/view/infrastructure/rabbitmq"
)

// newTransactionalPostViewedHandler wires the handler exactly as the bootstrap does:
// real transactor, idempotency, history and posts repositories.
func newTransactionalPostViewedHandler(t *testing.T, ctx context.Context, db *mongodriver.Database) (*rabbitmq.PostViewedHandler, *postmongo.Repository) {
	t.Helper()

	idempotencyRepository := idempotency.NewRepository(db)
	history := viewmongo.NewHistoryRepository(db)
	posts := postmongo.NewRepository(db)
	if err := idempotencyRepository.EnsureIndexes(ctx); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}
	if err := history.EnsureIndexes(ctx, 90*24*time.Hour); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}
	if err := posts.EnsureIndexes(ctx); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))

	return rabbitmq.NewPostViewedHandler(database.NewTransactor(db.Client()), idempotencyRepository, usecase.NewRecordPostView(history, posts, logger), logger), posts
}

func TestPostViewedHandler_ConcurrentViewsOfOnePostKeepOneRowWithTheLatestTimeAndCountEachViewOnce(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	db := startTransactionalMongo(t, ctx)
	handler, posts := newTransactionalPostViewedHandler(t, ctx, db)

	viewer, postID := uuid.NewString(), uuid.NewString()
	if err := posts.Insert(ctx, postusecase.CreatePostInput{PostID: uuid.MustParse(postID), UserID: uuid.New(), PostType: "BASIC"}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}
	base := time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC)
	payloads := make([][]byte, 0, 10)
	for minute := range 10 {
		payloads = append(payloads, []byte(fmt.Sprintf(`{"id":"%s","correlationId":"%s","occurredAt":"2026-03-01T11:00:00Z","viewId":"%s","postId":"%s","userId":"%s","source":"USER_PROFILE","feedPosition":0,"durationMs":1000,"timeWatchedMs":1000,"completionPercent":100,"exitReason":"NAVIGATED_AWAY","serverTimestamp":"%s","replayCount":%d}`,
			uuid.NewString(), uuid.NewString(), uuid.NewString(), postID, viewer, base.Add(time.Duration(minute)*time.Minute).Format(time.RFC3339Nano), minute+1)))
	}

	// Every view at once, each also delivered twice, retried the way the retry
	// middleware does: the first views race to create the row.
	var group sync.WaitGroup
	failures := make(chan error, 2*len(payloads))
	for _, payload := range append(payloads, payloads...) {
		group.Add(1)
		go func() {
			defer group.Done()
			var err error
			for range 10 {
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

	filter := bson.D{{Key: "user_id", Value: viewer}, {Key: "post_id", Value: postID}}
	count, err := db.Collection(viewmongo.CollectionName).CountDocuments(ctx, filter)
	if err != nil {
		t.Fatalf("CountDocuments() error = %v", err)
	}
	if count != 1 {
		t.Fatalf("view_history rows = %d, want 1", count)
	}
	var row struct {
		LastViewedAt time.Time `bson:"last_viewed_at"`
	}
	if err := db.Collection(viewmongo.CollectionName).FindOne(ctx, filter).Decode(&row); err != nil {
		t.Fatalf("FindOne() error = %v", err)
	}
	if want := base.Add(9 * time.Minute); !row.LastViewedAt.Equal(want) {
		t.Fatalf("last_viewed_at = %v, want the latest view %v", row.LastViewedAt, want)
	}

	// Ten distinct events, each delivered twice and racing on the same post: every event
	// counts exactly once, and the counter never touches the version.
	if got := storedPostViews(t, ctx, db, postID); got.ViewCount != int64(len(payloads)) || got.Version != 1 {
		t.Fatalf("stats.view_count/version = %d/%d, want %d/1", got.ViewCount, got.Version, len(payloads))
	}
}

func TestPostViewedHandler_AViewOfAnUnprojectedPostIsAckedRecordedAndCreatesNoPost(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	db := startTransactionalMongo(t, ctx)
	handler, _ := newTransactionalPostViewedHandler(t, ctx, db)

	eventID, viewer, postID := uuid.NewString(), uuid.NewString(), uuid.NewString()
	payload := []byte(fmt.Sprintf(`{"id":"%s","correlationId":"%s","occurredAt":"2026-03-01T11:00:00Z","viewId":"%s","postId":"%s","userId":"%s","source":"HOME_FEED","feedPosition":0,"durationMs":1000,"timeWatchedMs":1000,"completionPercent":100,"exitReason":"SCROLL_NEXT","serverTimestamp":"2026-03-01T11:00:00Z","replayCount":0}`,
		eventID, uuid.NewString(), uuid.NewString(), postID, viewer))

	if err := handler.Handle(ctx, message.NewMessage(uuid.NewString(), payload)); err != nil {
		t.Fatalf("Handle() error = %v, want nil (acked) for a view of a post that is not projected", err)
	}

	rows, err := db.Collection(viewmongo.CollectionName).CountDocuments(ctx, bson.D{{Key: "user_id", Value: viewer}, {Key: "post_id", Value: postID}})
	if err != nil {
		t.Fatalf("CountDocuments() error = %v", err)
	}
	if rows != 1 {
		t.Fatalf("view_history rows = %d, want the history recorded", rows)
	}
	postCount, err := db.Collection("posts").CountDocuments(ctx, bson.D{})
	if err != nil {
		t.Fatalf("CountDocuments() error = %v", err)
	}
	if postCount != 0 {
		t.Fatalf("posts documents = %d, want 0: a view never creates a post", postCount)
	}
	processed, err := idempotency.NewRepository(db).IsProcessed(ctx, uuid.MustParse(eventID))
	if err != nil || !processed {
		t.Fatalf("IsProcessed() = %v/%v, want the event marked processed", processed, err)
	}
}

type postViews struct {
	ViewCount int64
	Version   int64
}

func storedPostViews(t *testing.T, ctx context.Context, db *mongodriver.Database, postID string) postViews {
	t.Helper()

	var doc struct {
		Stats struct {
			ViewCount int64 `bson:"view_count"`
		} `bson:"stats"`
		Version int64 `bson:"version"`
	}
	if err := db.Collection("posts").FindOne(ctx, bson.D{{Key: "post_id", Value: postID}}).Decode(&doc); err != nil {
		t.Fatalf("FindOne() error = %v", err)
	}

	return postViews{ViewCount: doc.Stats.ViewCount, Version: doc.Version}
}

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

	return client.Database("post_query_view_transaction_test")
}
