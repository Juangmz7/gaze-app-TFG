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
func newTransactionalPostViewedHandler(t *testing.T, ctx context.Context, db *mongodriver.Database, dailyLimit int) (*rabbitmq.PostViewedHandler, *postmongo.Repository) {
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

	return rabbitmq.NewPostViewedHandler(database.NewTransactor(db.Client()), idempotencyRepository, usecase.NewRecordPostView(history, posts, dailyLimit, logger), logger), posts
}

func TestPostViewedHandler_ConcurrentViewsOfOnePostKeepOneRowWithTheLatestTimeAndCountEachViewOnce(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	db := startTransactionalMongo(t, ctx)
	// A limit above the ten views, so that every distinct event counts.
	handler, posts := newTransactionalPostViewedHandler(t, ctx, db, 100)

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
	handler, _ := newTransactionalPostViewedHandler(t, ctx, db, 5)

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

// viewedPayload is the literal JSON of a PostViewedEvent of viewer on postID at
// serverTimestamp, with a new event id unless eventID is given.
func viewedPayload(eventID, postID, viewer string, serverTimestamp time.Time) []byte {
	if eventID == "" {
		eventID = uuid.NewString()
	}
	return []byte(fmt.Sprintf(`{"id":"%s","correlationId":"%s","occurredAt":"%s","viewId":"%s","postId":"%s","userId":"%s","source":"HOME_FEED","feedPosition":0,"durationMs":1000,"timeWatchedMs":1000,"completionPercent":100,"exitReason":"SCROLL_NEXT","serverTimestamp":"%s","replayCount":0}`,
		eventID, uuid.NewString(), serverTimestamp.Add(time.Second).Format(time.RFC3339Nano), uuid.NewString(), postID, viewer, serverTimestamp.Format(time.RFC3339Nano)))
}

func insertViewedPost(t *testing.T, ctx context.Context, posts *postmongo.Repository) string {
	t.Helper()

	postID := uuid.New()
	if err := posts.Insert(ctx, postusecase.CreatePostInput{PostID: postID, UserID: uuid.New(), PostType: "BASIC"}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}
	return postID.String()
}

func TestPostViewedHandler_ConcurrentViewsOfTheSameUserAndPostOnOneDayIncreaseTheViewCountByExactlyTheLimit(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	db := startTransactionalMongo(t, ctx)
	const dailyLimit = 5
	handler, posts := newTransactionalPostViewedHandler(t, ctx, db, dailyLimit)
	viewer, postID := uuid.NewString(), insertViewedPost(t, ctx, posts)
	base := time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC)

	// Ten views of one day at once, each also delivered twice, retried the way the retry
	// middleware does.
	payloads := make([][]byte, 0, 10)
	for second := range 10 {
		payloads = append(payloads, viewedPayload("", postID, viewer, base.Add(time.Duration(second)*time.Second)))
	}
	var group sync.WaitGroup
	failures := make(chan error, 2*len(payloads))
	for _, payload := range append(payloads, payloads...) {
		group.Add(1)
		go func() {
			defer group.Done()
			var err error
			for range 20 {
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

	if got := storedPostViews(t, ctx, db, postID); got.ViewCount != dailyLimit || got.Version != 1 {
		t.Fatalf("stats.view_count/version = %d/%d, want %d/1", got.ViewCount, got.Version, dailyLimit)
	}
	row := storedHistoryRow(t, ctx, db, viewer, postID)
	if row.CountedDay != "2026-03-01" || row.CountedViews != dailyLimit || !row.LastViewedAt.Equal(base.Add(9*time.Second)) {
		t.Fatalf("history row = %+v, want 2026-03-01, %d views counted and the latest view", row, dailyLimit)
	}
}

func TestPostViewedHandler_CapsTheViewCountPerUserPostAndUTCDay(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	db := startTransactionalMongo(t, ctx)
	handler, posts := newTransactionalPostViewedHandler(t, ctx, db, 5)
	viewer, other, postID := uuid.NewString(), uuid.NewString(), insertViewedPost(t, ctx, posts)
	dayOne := time.Date(2026, 3, 1, 20, 0, 0, 0, time.UTC)
	handle := func(payload []byte) {
		t.Helper()
		if err := handler.Handle(ctx, message.NewMessage(uuid.NewString(), payload)); err != nil {
			t.Fatalf("Handle() error = %v", err)
		}
	}
	wantViews := func(want int64) {
		t.Helper()
		if got := storedPostViews(t, ctx, db, postID).ViewCount; got != want {
			t.Fatalf("stats.view_count = %d, want %d", got, want)
		}
	}

	// Five views on one day count five.
	for minute := range 5 {
		handle(viewedPayload("", postID, viewer, dayOne.Add(time.Duration(minute)*time.Minute)))
	}
	wantViews(5)

	// The 6th and 7th do not count, but still move the history entry.
	for minute := 5; minute < 7; minute++ {
		viewedAt := dayOne.Add(time.Duration(minute) * time.Minute)
		handle(viewedPayload("", postID, viewer, viewedAt))
		wantViews(5)
		if row := storedHistoryRow(t, ctx, db, viewer, postID); !row.LastViewedAt.Equal(viewedAt) {
			t.Fatalf("last_viewed_at = %v, want %v after a view over the limit", row.LastViewedAt, viewedAt)
		}
	}

	// Another user's five views count too.
	for minute := range 5 {
		handle(viewedPayload("", postID, other, dayOne.Add(time.Duration(minute)*time.Minute)))
	}
	wantViews(10)

	// The next UTC day counts again, up to five.
	dayTwo := time.Date(2026, 3, 2, 0, 0, 0, 0, time.UTC)
	for minute := range 6 {
		handle(viewedPayload("", postID, viewer, dayTwo.Add(time.Duration(minute)*time.Minute)))
	}
	wantViews(15)

	// A late view of day one neither counts nor resets day two.
	handle(viewedPayload("", postID, viewer, dayOne.Add(30*time.Minute)))
	wantViews(15)
	if row := storedHistoryRow(t, ctx, db, viewer, postID); row.CountedDay != "2026-03-02" || row.CountedViews != 5 {
		t.Fatalf("history row = %+v, want day two's count kept", row)
	}
}

func TestPostViewedHandler_ARedeliveredViewNeitherCountsTwiceNorUsesUpADailyView(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	db := startTransactionalMongo(t, ctx)
	handler, posts := newTransactionalPostViewedHandler(t, ctx, db, 3)
	viewer, postID := uuid.NewString(), insertViewedPost(t, ctx, posts)
	base := time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC)

	redelivered := viewedPayload("", postID, viewer, base)
	for range 3 {
		if err := handler.Handle(ctx, message.NewMessage(uuid.NewString(), redelivered)); err != nil {
			t.Fatalf("Handle() error = %v", err)
		}
	}
	if row := storedHistoryRow(t, ctx, db, viewer, postID); row.CountedViews != 1 {
		t.Fatalf("counted_views = %d after three deliveries of one event, want 1", row.CountedViews)
	}
	// Two more views still fit in the limit of 3, and the 4th does not.
	for minute := 1; minute <= 3; minute++ {
		if err := handler.Handle(ctx, message.NewMessage(uuid.NewString(), viewedPayload("", postID, viewer, base.Add(time.Duration(minute)*time.Minute)))); err != nil {
			t.Fatalf("Handle() error = %v", err)
		}
	}
	if got := storedPostViews(t, ctx, db, postID).ViewCount; got != 3 {
		t.Fatalf("stats.view_count = %d, want 3", got)
	}
}

type storedHistory struct {
	LastViewedAt time.Time `bson:"last_viewed_at"`
	CountedDay   string    `bson:"counted_day"`
	CountedViews int       `bson:"counted_views"`
}

func storedHistoryRow(t *testing.T, ctx context.Context, db *mongodriver.Database, viewer, postID string) storedHistory {
	t.Helper()

	var row storedHistory
	filter := bson.D{{Key: "user_id", Value: viewer}, {Key: "post_id", Value: postID}}
	if err := db.Collection(viewmongo.CollectionName).FindOne(ctx, filter).Decode(&row); err != nil {
		t.Fatalf("FindOne() error = %v", err)
	}
	return row
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
