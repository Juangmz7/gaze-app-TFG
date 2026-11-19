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

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/database"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/idempotency"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/view/application/usecase"
	viewmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/view/infrastructure/mongo"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/view/infrastructure/rabbitmq"
)

func TestPostViewedHandler_ConcurrentViewsOfOnePostKeepOneRowWithTheLatestTime(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	db := startTransactionalMongo(t, ctx)
	idempotencyRepository := idempotency.NewRepository(db)
	history := viewmongo.NewHistoryRepository(db)
	if err := idempotencyRepository.EnsureIndexes(ctx); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}
	if err := history.EnsureIndexes(ctx, 90*24*time.Hour); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}
	handler := rabbitmq.NewPostViewedHandler(database.NewTransactor(db.Client()), idempotencyRepository, usecase.NewRecordPostView(history), slog.New(slog.NewTextHandler(io.Discard, nil)))

	viewer, postID := uuid.NewString(), uuid.NewString()
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
