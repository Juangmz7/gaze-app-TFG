package bootstrap_test

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"testing"
	"time"

	"github.com/google/uuid"
	rawamqp "github.com/rabbitmq/amqp091-go"
	"github.com/testcontainers/testcontainers-go/modules/mongodb"
	"github.com/testcontainers/testcontainers-go/modules/rabbitmq"
	"go.mongodb.org/mongo-driver/v2/bson"
	mongodriver "go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	feedusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
	feedmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/infrastructure/mongo"
	feedrabbitmq "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/infrastructure/rabbitmq"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/database"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/outbox"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/bootstrap"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/publisher"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/topology"
)

const testInstant = "2026-03-01T10:15:30.123456Z"

// javaEventsApp is the real consumer stack from bootstrap.Bootstrap against a real
// broker and a real replica set, fed with the exact JSON the Java publishers emit.
type javaEventsApp struct {
	amqpURI  string
	db       *mongodriver.Database
	store    *outbox.Store
	relay    *outbox.Relay
	prefetch *feedusecase.RequestFeedPrefetchUsecase
	backlog  *feedusecase.FeedBacklog
	lease    *feedusecase.FeedLeaseManager
}

// Prefetch limits of the test stack, the production defaults.
const (
	testMaxUnserved = 60
	testExcludedCap = 200
)

// newPrefetchStack wires one publisher and the usecases around it the way main does,
// with the real outbox relay sending right after each commit. No batch runs: tests that
// need one call RelayBatch themselves.
func newPrefetchStack(t *testing.T, ctx context.Context, amqpURI string, db *mongodriver.Database, logger *slog.Logger) *javaEventsApp {
	t.Helper()

	store := outbox.NewStore(db)
	if err := store.EnsureIndexes(ctx); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}
	sessions := feedmongo.NewSessionRepository(db)
	if err := sessions.EnsureIndexes(ctx); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}
	units := feedmongo.NewFeedUnitRepository(db)
	if err := units.EnsureIndexes(ctx, time.Hour); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}

	amqpPublisher := outbox.NewAMQPPublisher(amqpURI, logger)
	t.Cleanup(func() { amqpPublisher.Close() })
	relay := outbox.NewRelay(store, amqpPublisher, outbox.DefaultRelayConfig(), logger)
	t.Cleanup(relay.Wait)
	store.SendImmediatelyWith(relay)

	lease := feedusecase.NewFeedLeaseManager(sessions, feedusecase.FeedLeaseConfig{Owner: "test-replica", TTL: time.Minute, RequestTTL: 30 * time.Second, ReleaseTimeout: 5 * time.Second}, logger)
	backlog := feedusecase.NewFeedBacklog(units, feedusecase.FeedBacklogConfig{MaxUnserved: testMaxUnserved, ExcludedIDsCap: testExcludedCap}, logger)
	prefetch := feedusecase.NewRequestFeedPrefetch(database.NewTransactor(db.Client()), backlog, lease, sessions,
		feedrabbitmq.NewFeedPrefetchPublisher(publisher.NewPublisher(store)), 10*time.Second, logger)

	return &javaEventsApp{amqpURI: amqpURI, db: db, store: store, relay: relay, prefetch: prefetch, backlog: backlog, lease: lease}
}

func startJavaEventsApp(t *testing.T, ctx context.Context) *javaEventsApp {
	t.Helper()

	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	amqpURI := startBroker(t, ctx)
	db := startReplicaSet(t, ctx)

	app := newPrefetchStack(t, ctx, amqpURI, db, logger)

	result, err := bootstrap.Bootstrap(ctx, amqpURI, db, bootstrap.Dependencies{Prefetch: app.prefetch, Lease: app.lease}, logger)
	if err != nil {
		t.Fatalf("Bootstrap() error = %v", err)
	}
	t.Cleanup(func() {
		if closeErr := result.Close(); closeErr != nil {
			t.Logf("Close() error = %v", closeErr)
		}
	})

	routerCtx, stopRouter := context.WithCancel(ctx)
	t.Cleanup(stopRouter)
	go func() {
		if runErr := result.Router.Run(routerCtx); runErr != nil {
			t.Logf("Router.Run() error = %v", runErr)
		}
	}()

	select {
	case <-result.Router.Running():
	case <-time.After(30 * time.Second):
		t.Fatal("timed out waiting for the router to start running")
	}

	return app
}

func (a *javaEventsApp) publish(t *testing.T, ctx context.Context, exchange, routingKey, body string) {
	t.Helper()

	conn, err := rawamqp.Dial(a.amqpURI)
	if err != nil {
		t.Fatalf("amqp.Dial() error = %v", err)
	}
	defer conn.Close()

	channel, err := conn.Channel()
	if err != nil {
		t.Fatalf("conn.Channel() error = %v", err)
	}
	defer channel.Close()

	if err := channel.PublishWithContext(ctx, exchange, routingKey, true, false, rawamqp.Publishing{
		ContentType: "application/json",
		Body:        []byte(body),
	}); err != nil {
		t.Fatalf("PublishWithContext() error = %v", err)
	}
}

// waitUntilDrained waits until the queue has no ready messages and its dead-letter
// queue holds exactly wantDLQ, failing as soon as the dead-letter queue exceeds it.
func (a *javaEventsApp) waitUntilDrained(t *testing.T, ctx context.Context, queue string, wantDLQ int) {
	t.Helper()

	conn, err := rawamqp.Dial(a.amqpURI)
	if err != nil {
		t.Fatalf("amqp.Dial() error = %v", err)
	}
	defer conn.Close()

	deadline := time.Now().Add(60 * time.Second)
	for {
		channel, err := conn.Channel()
		if err != nil {
			t.Fatalf("conn.Channel() error = %v", err)
		}
		main, mainErr := channel.QueueInspect(queue)
		if mainErr != nil {
			t.Fatalf("QueueInspect(%s) error = %v", queue, mainErr)
		}
		dead, deadErr := channel.QueueInspect(topology.DLQName(queue))
		if deadErr != nil {
			t.Fatalf("QueueInspect(dlq) error = %v", deadErr)
		}
		channel.Close()

		if main.Messages == 0 && dead.Messages == wantDLQ {
			return
		}
		if dead.Messages > wantDLQ {
			t.Fatalf("queue %s dead-letter depth = %d, want %d", queue, dead.Messages, wantDLQ)
		}
		if time.Now().After(deadline) {
			t.Fatalf("timed out draining %s: messages = %d, dead-letter depth = %d, want %d", queue, main.Messages, dead.Messages, wantDLQ)
		}

		select {
		case <-time.After(100 * time.Millisecond):
		case <-ctx.Done():
			t.Fatalf("context cancelled while draining %s", queue)
		}
	}
}

// waitForDocument polls until a document matches filter.
func (a *javaEventsApp) waitForDocument(t *testing.T, ctx context.Context, collection string, filter bson.D, out any) {
	t.Helper()

	deadline := time.Now().Add(30 * time.Second)
	for {
		err := a.db.Collection(collection).FindOne(ctx, filter).Decode(out)
		if err == nil {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for a document in %s matching %v: %v", collection, filter, err)
		}
		select {
		case <-time.After(100 * time.Millisecond):
		case <-ctx.Done():
			t.Fatalf("context cancelled while waiting for a document in %s", collection)
		}
	}
}

type postStats struct {
	LikeCount    int64 `bson:"like_count"`
	CommentCount int64 `bson:"comment_count"`
	ShareCount   int64 `bson:"share_count"`
}

// waitForStats polls until the post's stats equal want: the counters settle only
// once the deliveries ahead of them are consumed.
func (a *javaEventsApp) waitForStats(t *testing.T, ctx context.Context, postID string, want postStats) {
	t.Helper()

	deadline := time.Now().Add(30 * time.Second)
	var got struct {
		Stats postStats `bson:"stats"`
	}
	for {
		err := a.db.Collection("posts").FindOne(ctx, bson.D{{Key: "post_id", Value: postID}}).Decode(&got)
		if err == nil && got.Stats == want {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("post %s stats = %+v (err %v), want %+v", postID, got.Stats, err, want)
		}
		select {
		case <-time.After(100 * time.Millisecond):
		case <-ctx.Done():
			t.Fatalf("context cancelled while waiting for the stats of %s", postID)
		}
	}
}

func envelope() string {
	return fmt.Sprintf(`"id":"%s","correlationId":"%s","occurredAt":"%s"`, uuid.NewString(), uuid.NewString(), testInstant)
}

func postCreatedJSON(postID, userID string) string {
	return fmt.Sprintf(`{%s,"postId":"%s","userId":"%s","collabId":null,"postType":"BASIC","description":"Sunset over the harbour","postTags":["travel","sunset"],"media":[{"id":"%s","url":"https://media.example.test/sunset.jpg","thumbnailUrl":null,"mediaType":"IMAGE","taggedUsers":["ana_rivera"],"order":1},{"id":"%s","url":"https://media.example.test/harbour.mp4","thumbnailUrl":"https://media.example.test/harbour-thumb.jpg","mediaType":"VIDEO","duration":15000,"taggedUsers":[],"order":2}],"createdAt":"%s","updatedAt":"%s"}`,
		envelope(), postID, userID, uuid.NewString(), uuid.NewString(), testInstant, testInstant)
}

func likeCreatedJSON(postID, userID string) string {
	return fmt.Sprintf(`{%s,"postId":"%s","userId":"%s","source":"HOME_FEED","feedPosition":3,"createdAt":"%s"}`, envelope(), postID, userID, testInstant)
}

func TestConsumers_ProjectARealCamelCasePostCreatedEventWithItsMedia(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
	defer cancel()
	app := startJavaEventsApp(t, ctx)

	postID, userID := uuid.NewString(), uuid.NewString()
	app.publish(t, ctx, topology.ExchangePostEvents, topology.RKPostCreated, postCreatedJSON(postID, userID))

	var post struct {
		UserID      string   `bson:"user_id"`
		Description string   `bson:"description"`
		Tags        []string `bson:"tags"`
		Media       []struct {
			URL          string  `bson:"url"`
			ThumbnailURL *string `bson:"thumbnail_url"`
			MediaType    string  `bson:"media_type"`
			DurationMS   *int64  `bson:"duration_ms"`
			Order        int     `bson:"order"`
		} `bson:"media"`
	}
	app.waitForDocument(t, ctx, "posts", bson.D{{Key: "post_id", Value: postID}}, &post)

	if post.UserID != userID || post.Description != "Sunset over the harbour" || len(post.Tags) != 2 {
		t.Fatalf("stored post = %+v", post)
	}
	if len(post.Media) != 2 || post.Media[0].MediaType != "IMAGE" || post.Media[1].MediaType != "VIDEO" {
		t.Fatalf("stored media = %+v", post.Media)
	}
	if post.Media[1].DurationMS == nil || *post.Media[1].DurationMS != 15000 || post.Media[1].Order != 2 {
		t.Fatalf("stored video = %+v", post.Media[1])
	}
	app.waitUntilDrained(t, ctx, topology.QueuePost, 0)
}

func TestConsumers_ProjectARealCamelCaseUserRegisteredEventAndUserDeletedEvent(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
	defer cancel()
	app := startJavaEventsApp(t, ctx)

	userID := uuid.NewString()
	app.publish(t, ctx, topology.ExchangeUserEvents, topology.RKUserRegistered, fmt.Sprintf(
		`{%s,"userId":"%s","username":"ana_rivera","email":"ana@example.test","bio":{"description":"Photographer","socialMedia":{"instagram":"ana.rivera"}}}`,
		envelope(), userID))

	var user struct {
		Username  string    `bson:"username"`
		Email     string    `bson:"email"`
		CreatedAt time.Time `bson:"created_at"`
	}
	app.waitForDocument(t, ctx, "users", bson.D{{Key: "user_id", Value: userID}}, &user)

	if user.Username != "ana_rivera" || user.Email != "ana@example.test" {
		t.Fatalf("stored user = %+v", user)
	}
	if want, _ := time.Parse(time.RFC3339Nano, testInstant); !user.CreatedAt.Equal(want.Truncate(time.Millisecond)) {
		t.Fatalf("stored created_at = %v, want occurredAt %v", user.CreatedAt, want)
	}
	app.waitUntilDrained(t, ctx, topology.QueueUserFast, 0)

	app.publish(t, ctx, topology.ExchangeUserEvents, topology.RKUserDeleted, fmt.Sprintf(`{%s,"userId":"%s"}`, envelope(), userID))
	app.waitUntilDrained(t, ctx, topology.QueueUserSlow, 0)
	deadline := time.Now().Add(30 * time.Second)
	for {
		count, err := app.db.Collection("users").CountDocuments(ctx, bson.D{{Key: "user_id", Value: userID}})
		if err != nil {
			t.Fatalf("CountDocuments() error = %v", err)
		}
		if count == 0 {
			return
		}
		if time.Now().After(deadline) {
			t.Fatal("timed out waiting for the user to be deleted")
		}
		select {
		case <-time.After(100 * time.Millisecond):
		case <-ctx.Done():
			t.Fatal("context cancelled while waiting for the user to be deleted")
		}
	}
}

func TestConsumers_ProjectARealCamelCaseCollabOpenedEvent(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
	defer cancel()
	app := startJavaEventsApp(t, ctx)

	collabID, postID, userID := uuid.NewString(), uuid.NewString(), uuid.NewString()
	app.publish(t, ctx, topology.ExchangePostEvents, topology.RKPostCollabOpenedPostCreated, fmt.Sprintf(
		`{%s,"collabId":"%s","title":"Harbour series","createdBy":"%s","collabStatus":"OPEN","collabCreatedAt":"%s","creatorMemberStatus":"ACCEPTED","creatorRole":"ADMIN","creatorMemberCreatedAt":"%s","postId":"%s","userId":"%s","postCollabId":"%s","postType":"COLLAB","description":"Sunset","postTags":[],"media":[],"postCreatedAt":"%s","postUpdatedAt":"%s","withPostCreated":true}`,
		envelope(), collabID, userID, testInstant, testInstant, postID, userID, collabID, testInstant, testInstant))

	var collab struct {
		PostID      string `bson:"post_id"`
		OwnerUserID string `bson:"owner_user_id"`
		Title       string `bson:"title"`
		Status      string `bson:"status"`
	}
	app.waitForDocument(t, ctx, "post_collabs", bson.D{{Key: "collab_id", Value: collabID}}, &collab)

	if collab.PostID != postID || collab.OwnerUserID != userID || collab.Title != "Harbour series" || collab.Status != "OPEN" {
		t.Fatalf("stored collab = %+v", collab)
	}
	app.waitUntilDrained(t, ctx, topology.QueuePost, 0)
}

func TestConsumers_StatsFollowRealEventsAndIgnoreRedeliveriesAndNoOpDeletes(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
	defer cancel()
	app := startJavaEventsApp(t, ctx)
	ex := topology.ExchangePostEvents

	postID, liker, commentID := uuid.NewString(), uuid.NewString(), uuid.NewString()
	app.publish(t, ctx, ex, topology.RKPostCreated, postCreatedJSON(postID, uuid.NewString()))

	// One like delivered twice (a broker redelivery), plus a different event for the
	// same (post, user) pair: the like counts once.
	like := likeCreatedJSON(postID, liker)
	app.publish(t, ctx, ex, topology.RKPostLikeCreated, like)
	app.publish(t, ctx, ex, topology.RKPostLikeCreated, like)
	app.publish(t, ctx, ex, topology.RKPostLikeCreated, likeCreatedJSON(postID, liker))
	app.publish(t, ctx, ex, topology.RKPostLikeCreated, likeCreatedJSON(postID, uuid.NewString()))

	comment := fmt.Sprintf(`{%s,"commentId":"%s","postId":"%s","userId":"%s","content":"Lovely light","replyTo":null,"createdAt":"%s","updatedAt":"%s"}`,
		envelope(), commentID, postID, liker, testInstant, testInstant)
	app.publish(t, ctx, ex, topology.RKPostCommentCreated, comment)
	app.publish(t, ctx, ex, topology.RKPostCommentCreated, comment)

	app.publish(t, ctx, ex, topology.RKPostShareCreated, fmt.Sprintf(`{%s,"postId":"%s","userId":"%s","createdAt":"%s"}`, envelope(), postID, liker, testInstant))

	app.waitForStats(t, ctx, postID, postStats{LikeCount: 2, CommentCount: 1, ShareCount: 1})

	likeDeleted := fmt.Sprintf(`{%s,"postId":"%s","userId":"%s","source":"HOME_FEED","feedPosition":3}`, envelope(), postID, liker)
	app.publish(t, ctx, ex, topology.RKPostLikeDeleted, likeDeleted)
	app.publish(t, ctx, ex, topology.RKPostLikeDeleted, likeDeleted)
	// A different unlike event for a like that is already gone removes nothing.
	app.publish(t, ctx, ex, topology.RKPostLikeDeleted, fmt.Sprintf(`{%s,"postId":"%s","userId":"%s","source":"HOME_FEED","feedPosition":3}`, envelope(), postID, liker))
	app.publish(t, ctx, ex, topology.RKPostCommentDeleted, fmt.Sprintf(`{%s,"commentId":"%s","postId":"%s","userId":"%s"}`, envelope(), commentID, postID, liker))
	app.publish(t, ctx, ex, topology.RKPostShareDeleted, fmt.Sprintf(`{%s,"postId":"%s","userId":"%s"}`, envelope(), postID, liker))
	// Nothing to remove and the counter is already 0: it stays at 0.
	app.publish(t, ctx, ex, topology.RKPostShareDeleted, fmt.Sprintf(`{%s,"postId":"%s","userId":"%s"}`, envelope(), postID, liker))

	app.waitForStats(t, ctx, postID, postStats{LikeCount: 1, CommentCount: 0, ShareCount: 0})
	app.waitUntilDrained(t, ctx, topology.QueuePost, 0)
}

// The like of a post that is not projected is retried and then dead-lettered, and
// its transaction leaves nothing behind.
func TestConsumers_LikeOfAnUnprojectedPostRollsBackAndIsRetriedThenDeadLettered(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
	defer cancel()
	app := startJavaEventsApp(t, ctx)

	postID := uuid.NewString()
	app.publish(t, ctx, topology.ExchangePostEvents, topology.RKPostLikeCreated, likeCreatedJSON(postID, uuid.NewString()))
	app.waitUntilDrained(t, ctx, topology.QueuePost, 1)

	likes, err := app.db.Collection("post_likes").CountDocuments(ctx, bson.D{{Key: "post_id", Value: postID}})
	if err != nil {
		t.Fatalf("CountDocuments() error = %v", err)
	}
	if likes != 0 {
		t.Fatalf("post_likes documents = %d, want 0: the transaction must roll the insert back", likes)
	}
	processed, err := app.db.Collection("processed_events").CountDocuments(ctx, bson.D{})
	if err != nil {
		t.Fatalf("CountDocuments() error = %v", err)
	}
	if processed != 0 {
		t.Fatalf("processed_events documents = %d, want 0: a failed event must stay unprocessed", processed)
	}
}

func startBroker(t *testing.T, ctx context.Context) string {
	t.Helper()

	container, err := rabbitmq.Run(ctx, "rabbitmq:4.3-management-alpine")
	if err != nil {
		t.Fatalf("rabbitmq.Run() error = %v", err)
	}
	t.Cleanup(func() {
		if terminateErr := container.Terminate(context.Background()); terminateErr != nil {
			t.Logf("container.Terminate() error = %v", terminateErr)
		}
	})

	amqpURI, err := container.AmqpURL(ctx)
	if err != nil {
		t.Fatalf("container.AmqpURL() error = %v", err)
	}

	return amqpURI
}

// startReplicaSet starts a single-node replica set, which transactions need, and
// connects directly so the driver does not resolve the advertised member host.
func startReplicaSet(t *testing.T, ctx context.Context) *mongodriver.Database {
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

	return client.Database("post_query_java_events_test")
}
