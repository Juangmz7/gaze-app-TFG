package bootstrap_test

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"slices"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	rawamqp "github.com/rabbitmq/amqp091-go"
	"go.mongodb.org/mongo-driver/v2/bson"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
	feedmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/infrastructure/mongo"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/database"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/outbox"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/publisher"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/topology"
)

// prefetchMessage is what recommendation-service reads, decoded from the broker.
type prefetchMessage struct {
	ID              string   `json:"id"`
	CorrelationID   string   `json:"correlationId"`
	OccurredAt      string   `json:"occurredAt"`
	UserID          string   `json:"userId"`
	Reason          string   `json:"reason"`
	CurrentFeedSize *int64   `json:"currentFeedSize"`
	ExcludedPostIDs []string `json:"excludedPostIds"`
}

// bindFeedRequests binds a queue to x.feed.events where recommendation-service binds
// its own, so a test sees what that service would.
func (a *javaEventsApp) bindFeedRequests(t *testing.T, ctx context.Context) <-chan rawamqp.Delivery {
	t.Helper()

	conn, err := rawamqp.Dial(a.amqpURI)
	if err != nil {
		t.Fatalf("amqp.Dial() error = %v", err)
	}
	t.Cleanup(func() { conn.Close() })
	channel, err := conn.Channel()
	if err != nil {
		t.Fatalf("conn.Channel() error = %v", err)
	}
	queue, err := channel.QueueDeclare("", false, true, true, false, nil)
	if err != nil {
		t.Fatalf("QueueDeclare() error = %v", err)
	}
	if err := channel.QueueBind(queue.Name, topology.RKFeedExhausted, topology.ExchangeFeedEvents, false, nil); err != nil {
		t.Fatalf("QueueBind() error = %v", err)
	}
	deliveries, err := channel.ConsumeWithContext(ctx, queue.Name, "", true, true, false, false, nil)
	if err != nil {
		t.Fatalf("ConsumeWithContext() error = %v", err)
	}
	return deliveries
}

func nextFeedRequest(t *testing.T, ctx context.Context, deliveries <-chan rawamqp.Delivery, userID string) prefetchMessage {
	t.Helper()

	timeout := time.After(30 * time.Second)
	for {
		select {
		case delivery := <-deliveries:
			var message prefetchMessage
			if err := json.Unmarshal(delivery.Body, &message); err != nil {
				t.Fatalf("Unmarshal(%s) error = %v", delivery.Body, err)
			}
			if message.UserID == userID {
				return message
			}
		case <-timeout:
			t.Fatalf("timed out waiting for a prefetch request for user %s", userID)
		case <-ctx.Done():
			t.Fatal("context cancelled while waiting for a prefetch request")
		}
	}
}

// outboxRequests is the authoritative count of requests stored for a user: it needs no
// waiting to prove that something was not published.
func (a *javaEventsApp) outboxRequests(t *testing.T, ctx context.Context, userID string) []prefetchMessage {
	t.Helper()

	cursor, err := a.db.Collection("outbox_events").Find(ctx, bson.D{{Key: "event_type", Value: publisher.EventType}})
	if err != nil {
		t.Fatalf("Find() error = %v", err)
	}
	var events []outbox.Event
	if err := cursor.All(ctx, &events); err != nil {
		t.Fatalf("cursor.All() error = %v", err)
	}
	requests := []prefetchMessage{}
	for _, event := range events {
		var message prefetchMessage
		if err := json.Unmarshal([]byte(event.Payload), &message); err != nil {
			t.Fatalf("Unmarshal(%s) error = %v", event.Payload, err)
		}
		if message.UserID == userID {
			requests = append(requests, message)
		}
	}
	return requests
}

type sessionState struct {
	PrefetchDue    bool       `bson:"prefetch_due"`
	LastPrefetchAt *time.Time `bson:"last_prefetch_at"`
	LeaseToken     *string    `bson:"prefetch_lease_token"`
	LeaseExpiresAt *time.Time `bson:"prefetch_lease_expires_at"`
}

func (a *javaEventsApp) session(t *testing.T, ctx context.Context, userID string) sessionState {
	t.Helper()

	var state sessionState
	if err := a.db.Collection("user_feed_sessions").FindOne(ctx, bson.D{{Key: "user_id", Value: userID}}).Decode(&state); err != nil {
		t.Fatalf("FindOne(session %s) error = %v", userID, err)
	}
	return state
}

// recommendedPostSent is the literal JSON recommendation-service's pydantic event
// produces.
func recommendedPostSent(eventID, correlationID, userID string, posts ...string) string {
	if posts == nil {
		posts = []string{}
	}
	list, _ := json.Marshal(posts)
	return fmt.Sprintf(`{"id":"%s","correlationId":"%s","occurredAt":"2026-11-05T10:15:30.123456+00:00","userId":"%s","posts":%s}`, eventID, correlationID, userID, list)
}

func (a *javaEventsApp) publishResponse(t *testing.T, ctx context.Context, body string) {
	t.Helper()
	a.publish(t, ctx, topology.ExchangeFeedEvents, topology.RKPostRecommendedSent, body)
}

func (a *javaEventsApp) waitForProcessed(t *testing.T, ctx context.Context, eventID string) {
	t.Helper()
	waitUntilCount(t, ctx, a.db.Collection("processed_events"), bson.D{{Key: "event_id", Value: uuid.MustParse(eventID)}}, 1)
}

func (a *javaEventsApp) sweep(sessions *feedmongo.SessionRepository) *usecase.SweepStaleFeedSessionsUsecase {
	return usecase.NewSweepStaleFeedSessions(sessions, a.prefetch, usecase.SweepStaleFeedSessionsConfig{StaleAfter: 3 * time.Minute, BatchSize: 2}, slog.New(slog.NewTextHandler(io.Discard, nil)))
}

func staleHeartbeat(t *testing.T, ctx context.Context, sessions *feedmongo.SessionRepository, userID uuid.UUID) {
	t.Helper()
	if err := sessions.RecordHeartbeat(ctx, userID, time.Now().UTC().Add(-10*time.Minute)); err != nil {
		t.Fatalf("RecordHeartbeat() error = %v", err)
	}
}

func disconnectRequest(mux *http.ServeMux) *httptest.ResponseRecorder {
	recorder := httptest.NewRecorder()
	mux.ServeHTTP(recorder, httptest.NewRequest(http.MethodPost, "/api/feed/disconnect", nil))
	return recorder
}

// disconnectUsecase builds the disconnect for any user, for tests that need more users
// than the stack's authenticated viewer.
func (a *javaEventsApp) disconnectUsecase(sessions *feedmongo.SessionRepository) *usecase.RegisterUserDisconnectedUsecase {
	return usecase.NewRegisterUserDisconnected(sessions, a.prefetch, 10*time.Second, slog.New(slog.NewTextHandler(io.Discard, nil)))
}

// One broker and one replica set for the whole group: every subtest has its own users,
// so they do not disturb each other.
func TestFeedPrefetchOnBroker(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()
	app := startFeedOnBroker(t, ctx)
	sessions := feedmongo.NewSessionRepository(app.db)
	requests := app.bindFeedRequests(t, ctx)

	t.Run("the disconnect endpoint publishes the prefetch and answers 202", func(t *testing.T) {
		recorder := disconnectRequest(app.mux)
		if recorder.Code != http.StatusAccepted {
			t.Fatalf("status = %d, want 202: %s", recorder.Code, recorder.Body)
		}

		message := nextFeedRequest(t, ctx, requests, app.viewer.String())
		if message.Reason != "disconnect" || message.CurrentFeedSize == nil || *message.CurrentFeedSize != 0 || message.ExcludedPostIDs == nil {
			t.Fatalf("message = %+v", message)
		}
		if uuid.Validate(message.ID) != nil || uuid.Validate(message.CorrelationID) != nil || message.OccurredAt == "" {
			t.Fatalf("envelope = %+v", message)
		}

		state := app.session(t, ctx, app.viewer.String())
		if state.LeaseToken == nil || *state.LeaseToken != message.CorrelationID {
			t.Fatalf("lease token = %v, want the event's correlationId %s", state.LeaseToken, message.CorrelationID)
		}
		if state.PrefetchDue || state.LastPrefetchAt == nil {
			t.Fatalf("session = %+v, want the marker cleared", state)
		}

		// Held until the response: a second trigger builds nothing.
		disconnectRequest(app.mux)
		if stored := app.outboxRequests(t, ctx, app.viewer.String()); len(stored) != 1 {
			t.Fatalf("requests = %+v, want the second disconnect denied by the lease", stored)
		}

		// Clean for the subtests that use the viewer.
		if released, err := sessions.ReleaseFeedLease(ctx, app.viewer, uuid.MustParse(message.CorrelationID)); err != nil || !released {
			t.Fatalf("ReleaseFeedLease() = %v, %v", released, err)
		}
	})

	t.Run("the request excludes what is waiting and what was just served", func(t *testing.T) {
		creator, viewer := uuid.NewString(), uuid.New()
		app.registerUser(t, ctx, creator, "excluded_"+creator[:8])
		posts := make([]string, 0, 3)
		for range 3 {
			post := uuid.NewString()
			app.createPost(t, ctx, post, creator)
			posts = append(posts, post)
		}
		app.buildUnits(t, ctx, viewer, posts...)
		if err := feedmongo.NewFeedUnitRepository(app.db).MarkServed(ctx, viewer.String(), posts[:1], time.Now().UTC()); err != nil {
			t.Fatalf("MarkServed() error = %v", err)
		}

		if err := app.disconnectUsecase(sessions).Execute(ctx, usecase.RegisterUserDisconnectedInput{UserID: viewer}); err != nil {
			t.Fatalf("Execute() error = %v", err)
		}

		message := nextFeedRequest(t, ctx, requests, viewer.String())
		if want := []string{posts[1], posts[2], posts[0]}; !slices.Equal(message.ExcludedPostIDs, want) {
			t.Fatalf("excludedPostIds = %v, want %v", message.ExcludedPostIDs, want)
		}
		if *message.CurrentFeedSize != 2 {
			t.Fatalf("currentFeedSize = %d, want 2", *message.CurrentFeedSize)
		}
	})

	t.Run("at the maximum no trigger publishes and the user stops being due", func(t *testing.T) {
		viewer := uuid.New()
		staleHeartbeat(t, ctx, sessions, viewer)
		units := make([]usecase.FeedUnit, 0, testMaxUnserved)
		for index := range testMaxUnserved {
			units = append(units, usecase.FeedUnit{UserID: viewer.String(), PostID: uuid.NewString(), Position: int64(index), PostType: usecase.FeedPostTypeBasic, IngestedAt: time.Now().UTC()})
		}
		if _, err := feedmongo.NewFeedUnitRepository(app.db).InsertUnits(ctx, units); err != nil {
			t.Fatalf("InsertUnits() error = %v", err)
		}

		output, err := app.prefetch.Execute(ctx, usecase.RequestFeedPrefetchInput{UserID: viewer, Reason: usecase.FeedPrefetchReasonStaleHeartbeat})
		if err != nil {
			t.Fatalf("Execute() error = %v", err)
		}

		if output.Status != usecase.RequestFeedPrefetchSkipped {
			t.Fatalf("status = %s, want skipped", output.Status)
		}
		if stored := app.outboxRequests(t, ctx, viewer.String()); len(stored) != 0 {
			t.Fatalf("requests = %+v, want none", stored)
		}
		state := app.session(t, ctx, viewer.String())
		if state.PrefetchDue || state.LeaseToken != nil {
			t.Fatalf("session = %+v, want no longer due and no lease", state)
		}
	})

	t.Run("the sweeper publishes one request per stale user across two runs", func(t *testing.T) {
		first, second, third := uuid.New(), uuid.New(), uuid.New()
		for _, userID := range []uuid.UUID{first, second, third} {
			staleHeartbeat(t, ctx, sessions, userID)
		}
		fresh := uuid.New()
		if err := sessions.RecordHeartbeat(ctx, fresh, time.Now().UTC()); err != nil {
			t.Fatalf("RecordHeartbeat() error = %v", err)
		}

		sweep := app.sweep(sessions)
		for run := range 2 {
			if _, err := sweep.Execute(ctx); err != nil {
				t.Fatalf("sweep %d error = %v", run, err)
			}
		}

		for _, userID := range []uuid.UUID{first, second, third} {
			if stored := app.outboxRequests(t, ctx, userID.String()); len(stored) != 1 || stored[0].Reason != "stale_heartbeat" {
				t.Fatalf("requests for %v = %+v, want exactly one stale_heartbeat", userID, stored)
			}
			nextFeedRequest(t, ctx, requests, userID.String())
		}
		if stored := app.outboxRequests(t, ctx, fresh.String()); len(stored) != 0 {
			t.Fatalf("requests for a fresh user = %+v, want none", stored)
		}
	})

	t.Run("a sweep and a disconnect racing for one user publish exactly once", func(t *testing.T) {
		disconnect := app.disconnectUsecase(sessions)
		sweep := app.sweep(sessions)

		const users = 8
		ids := make([]uuid.UUID, 0, users)
		for range users {
			userID := uuid.New()
			staleHeartbeat(t, ctx, sessions, userID)
			ids = append(ids, userID)
		}

		var wg sync.WaitGroup
		wg.Go(func() {
			if _, err := sweep.Execute(ctx); err != nil {
				t.Errorf("sweep error = %v", err)
			}
		})
		for _, userID := range ids {
			wg.Go(func() {
				if err := disconnect.Execute(ctx, usecase.RegisterUserDisconnectedInput{UserID: userID}); err != nil {
					t.Errorf("disconnect error = %v", err)
				}
			})
		}
		wg.Wait()

		for _, userID := range ids {
			if stored := app.outboxRequests(t, ctx, userID.String()); len(stored) != 1 {
				t.Fatalf("requests for %v = %+v, want exactly one", userID, stored)
			}
		}
	})

	t.Run("many transactions racing for a user with no document grant one lease", func(t *testing.T) {
		userID := uuid.New()

		const workers = 8
		outputs := make(chan usecase.RequestFeedPrefetchStatus, workers)
		var wg sync.WaitGroup
		for range workers {
			wg.Go(func() {
				output, err := app.prefetch.Execute(ctx, usecase.RequestFeedPrefetchInput{UserID: userID, Reason: usecase.FeedPrefetchReasonUserRegistered})
				if err != nil {
					t.Errorf("Execute() error = %v, want a clean denial", err)
					return
				}
				outputs <- output.Status
			})
		}
		wg.Wait()
		close(outputs)

		published := 0
		for status := range outputs {
			if status == usecase.RequestFeedPrefetchPublished {
				published++
			}
		}
		if published != 1 {
			t.Fatalf("published = %d, want exactly 1", published)
		}
		if stored := app.outboxRequests(t, ctx, userID.String()); len(stored) != 1 {
			t.Fatalf("requests = %+v, want exactly one", stored)
		}
	})

	t.Run("the denied lease leaves the transaction with the sentinel and nothing written", func(t *testing.T) {
		userID := uuid.New()
		if _, err := app.lease.Acquire(ctx, userID); err != nil {
			t.Fatalf("Acquire() error = %v", err)
		}

		var eventInserted bool
		err := database.WithTransaction(ctx, app.db.Client(), func(ctx context.Context) error {
			if _, err := app.db.Collection("lease_probe").InsertOne(ctx, bson.D{{Key: "user_id", Value: userID.String()}}); err != nil {
				return err
			}
			eventInserted = true
			_, err := app.lease.Acquire(ctx, userID)
			return err
		})

		if !errors.Is(err, usecase.ErrFeedLeaseDenied) {
			t.Fatalf("WithTransaction() error = %v, want the sentinel", err)
		}
		count, countErr := app.db.Collection("lease_probe").CountDocuments(ctx, bson.D{{Key: "user_id", Value: userID.String()}})
		if countErr != nil || !eventInserted || count != 0 {
			t.Fatalf("probe documents = %d, %v; want the write rolled back", count, countErr)
		}
	})

	t.Run("a failed outbox write rolls the lease back", func(t *testing.T) {
		userID := uuid.New()
		failing := usecase.NewRequestFeedPrefetch(database.NewTransactor(app.db.Client()), app.backlog, app.lease, sessions, failingPublisher{}, 10*time.Second, slog.New(slog.NewTextHandler(io.Discard, nil)))
		staleHeartbeat(t, ctx, sessions, userID)

		if _, err := failing.Execute(ctx, usecase.RequestFeedPrefetchInput{UserID: userID, Reason: usecase.FeedPrefetchReasonDisconnect}); err == nil {
			t.Fatal("Execute() error = nil, want the outbox failure")
		}

		state := app.session(t, ctx, userID.String())
		if state.LeaseToken != nil || !state.PrefetchDue {
			t.Fatalf("session = %+v, want no lease and still due", state)
		}
		if _, err := app.prefetch.Execute(ctx, usecase.RequestFeedPrefetchInput{UserID: userID, Reason: usecase.FeedPrefetchReasonDisconnect}); err != nil {
			t.Fatalf("next trigger error = %v, want it to acquire", err)
		}
	})

	t.Run("a lease left by a crashed instance is granted again after its TTL", func(t *testing.T) {
		userID := uuid.New()
		now := time.Now().UTC()
		crashed := usecase.FeedLease{UserID: userID, Owner: "crashed-replica", Token: uuid.New(), ExpiresAt: now.Add(-time.Second)}
		if err := sessions.AcquireFeedLease(ctx, crashed, now.Add(-time.Minute)); err != nil {
			t.Fatalf("AcquireFeedLease() error = %v", err)
		}

		output, err := app.prefetch.Execute(ctx, usecase.RequestFeedPrefetchInput{UserID: userID, Reason: usecase.FeedPrefetchReasonDisconnect})
		if err != nil || output.Status != usecase.RequestFeedPrefetchPublished {
			t.Fatalf("Execute() = %+v, %v; want published", output, err)
		}
	})

	t.Run("a registered user gets the prefetch without a presence document", func(t *testing.T) {
		userID := uuid.NewString()
		app.publish(t, ctx, topology.ExchangeUserEvents, topology.RKUserRegistered, fmt.Sprintf(
			`{%s,"userId":"%s","username":"%s","email":"new@example.test","bio":{"description":"Photographer","socialMedia":{}}}`,
			envelope(), userID, "new_"+userID[:8]))

		message := nextFeedRequest(t, ctx, requests, userID)
		if message.Reason != "user_registered" || len(message.ExcludedPostIDs) != 0 {
			t.Fatalf("message = %+v", message)
		}
		var user bson.M
		app.waitForDocument(t, ctx, "users", bson.D{{Key: "user_id", Value: userID}}, &user)
	})

	t.Run("a registered user whose lease is held is still projected", func(t *testing.T) {
		userID := uuid.NewString()
		if _, err := app.lease.Acquire(ctx, uuid.MustParse(userID)); err != nil {
			t.Fatalf("Acquire() error = %v", err)
		}

		app.publish(t, ctx, topology.ExchangeUserEvents, topology.RKUserRegistered, fmt.Sprintf(
			`{%s,"userId":"%s","username":"%s","email":"held@example.test","bio":{"description":"Photographer","socialMedia":{}}}`,
			envelope(), userID, "held_"+userID[:8]))

		var user bson.M
		app.waitForDocument(t, ctx, "users", bson.D{{Key: "user_id", Value: userID}}, &user)
		app.waitUntilDrained(t, ctx, topology.QueueUserFast, 0)
		if stored := app.outboxRequests(t, ctx, userID); len(stored) != 0 {
			t.Fatalf("requests = %+v, want none while the lease is held", stored)
		}
	})

	t.Run("a response builds the feed and releases the lease, a redelivery changes nothing", func(t *testing.T) {
		viewer := uuid.NewString()
		creator := uuid.NewString()
		app.registerUser(t, ctx, creator, "resp_"+creator[:8])
		app.registerUser(t, ctx, viewer, "view_"+viewer[:8])
		first, second := uuid.NewString(), uuid.NewString()
		app.createPost(t, ctx, first, creator)
		app.createPost(t, ctx, second, creator)

		// The registration above requested the prefetch and holds the lease.
		request := nextFeedRequest(t, ctx, requests, viewer)
		eventID := uuid.NewString()
		body := recommendedPostSent(eventID, request.CorrelationID, viewer, first, second)

		app.publishResponse(t, ctx, body)
		app.waitForProcessed(t, ctx, eventID)

		filter := bson.D{{Key: "user_id", Value: viewer}}
		waitUntilCount(t, ctx, app.db.Collection("feed_units"), filter, 2)
		state := app.session(t, ctx, viewer)
		if state.LeaseToken != nil || state.PrefetchDue || state.LastPrefetchAt == nil {
			t.Fatalf("session = %+v, want the lease released and the marker cleared", state)
		}

		before := app.session(t, ctx, viewer)
		app.publishResponse(t, ctx, body)
		app.waitUntilDrained(t, ctx, topology.QueueFeed, 0)
		waitUntilCount(t, ctx, app.db.Collection("feed_units"), filter, 2)
		app.waitForProcessed(t, ctx, eventID)
		if after := app.session(t, ctx, viewer); !after.LastPrefetchAt.Equal(*before.LastPrefetchAt) {
			t.Fatalf("last_prefetch_at moved from %v to %v, want the usecase not called", before.LastPrefetchAt, after.LastPrefetchAt)
		}
	})

	t.Run("a late response stores its units but keeps the newer lease", func(t *testing.T) {
		viewer, creator := uuid.NewString(), uuid.NewString()
		app.registerUser(t, ctx, creator, "late_"+creator[:8])
		app.registerUser(t, ctx, viewer, "lview_"+viewer[:8])
		post := uuid.NewString()
		app.createPost(t, ctx, post, creator)
		request := nextFeedRequest(t, ctx, requests, viewer)

		eventID := uuid.NewString()
		app.publishResponse(t, ctx, recommendedPostSent(eventID, uuid.NewString(), viewer, post))
		app.waitForProcessed(t, ctx, eventID)

		waitUntilCount(t, ctx, app.db.Collection("feed_units"), bson.D{{Key: "user_id", Value: viewer}}, 1)
		if state := app.session(t, ctx, viewer); state.LeaseToken == nil || *state.LeaseToken != request.CorrelationID {
			t.Fatalf("lease token = %v, want %s untouched", state.LeaseToken, request.CorrelationID)
		}
	})

	t.Run("an empty response still releases the lease", func(t *testing.T) {
		viewer := uuid.NewString()
		app.registerUser(t, ctx, viewer, "empty_"+viewer[:8])
		request := nextFeedRequest(t, ctx, requests, viewer)

		eventID := uuid.NewString()
		app.publishResponse(t, ctx, recommendedPostSent(eventID, request.CorrelationID, viewer))
		app.waitForProcessed(t, ctx, eventID)

		if state := app.session(t, ctx, viewer); state.LeaseToken != nil {
			t.Fatalf("lease token = %v, want it released", *state.LeaseToken)
		}
	})

	t.Run("a response for a deleted user is acknowledged and dropped", func(t *testing.T) {
		gone, creator := uuid.NewString(), uuid.NewString()
		app.registerUser(t, ctx, creator, "gone_"+creator[:8])
		post := uuid.NewString()
		app.createPost(t, ctx, post, creator)

		eventID := uuid.NewString()
		app.publishResponse(t, ctx, recommendedPostSent(eventID, uuid.NewString(), gone, post))
		app.waitForProcessed(t, ctx, eventID)

		if count := app.countUnits(t, ctx, bson.D{{Key: "user_id", Value: gone}}); count != 0 {
			t.Fatalf("units = %d, want none", count)
		}
		count, err := app.db.Collection("user_feed_sessions").CountDocuments(ctx, bson.D{{Key: "user_id", Value: gone}})
		if err != nil || count != 0 {
			t.Fatalf("sessions = %d, %v; want the user not brought back", count, err)
		}
	})

	t.Run("a trending delivery keeps being acknowledged without a handler", func(t *testing.T) {
		app.publish(t, ctx, topology.ExchangeFeedEvents, topology.RKPostTrendingSent, `{"id":"not validated"}`)
		app.waitUntilDrained(t, ctx, topology.QueueFeed, 0)
	})

	// Last: it is the only subtest that leaves a message in the dead-letter queue.
	t.Run("a malformed response lands in the dead-letter queue without retries", func(t *testing.T) {
		started := time.Now()
		app.publishResponse(t, ctx, `{"id":"not-a-uuid","posts":"nope"}`)
		app.waitUntilDrained(t, ctx, topology.QueueFeed, 1)
		// The first retry waits 2 s, so a message that got here sooner consumed none.
		if elapsed := time.Since(started); elapsed >= 2*time.Second {
			t.Fatalf("dead-lettered after %s, want no retry wait", elapsed)
		}
	})
}

type failingPublisher struct{}

func (failingPublisher) PublishFeedPrefetch(context.Context, usecase.FeedPrefetchRequest) error {
	return errors.New("outbox insert failed")
}

// disconnect -> request on the broker -> response consumed -> a claim serves the page.
func TestFeedPrefetchOnBroker_EndToEndFromDisconnectToClaim(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Minute)
	defer cancel()
	app := startFeedOnBroker(t, ctx)
	requests := app.bindFeedRequests(t, ctx)

	viewer, creator := app.viewer.String(), uuid.NewString()
	app.registerUser(t, ctx, creator, "ana_rivera")
	first, second := uuid.NewString(), uuid.NewString()
	app.createPost(t, ctx, first, creator)
	app.createPost(t, ctx, second, creator)
	app.registerUser(t, ctx, viewer, "viewer_user")
	registered := nextFeedRequest(t, ctx, requests, viewer)
	empty := uuid.NewString()
	app.publishResponse(t, ctx, recommendedPostSent(empty, registered.CorrelationID, viewer))
	app.waitForProcessed(t, ctx, empty)

	if recorder := disconnectRequest(app.mux); recorder.Code != http.StatusAccepted {
		t.Fatalf("status = %d, want 202", recorder.Code)
	}
	request := nextFeedRequest(t, ctx, requests, viewer)
	if request.Reason != "disconnect" {
		t.Fatalf("reason = %s, want disconnect", request.Reason)
	}

	eventID := uuid.NewString()
	app.publishResponse(t, ctx, recommendedPostSent(eventID, request.CorrelationID, viewer, second, first))
	app.waitForProcessed(t, ctx, eventID)

	cards := app.claim(t, "")
	if len(cards) != 2 || cards[0]["postId"] != second || cards[1]["postId"] != first {
		t.Fatalf("cards = %v, want [%s %s] in the recommended order", cards, second, first)
	}

	// That claim left less than a page: the low watermark asks for more, excluding what
	// was just served, without any request waiting for it.
	lowWatermark := nextFeedRequest(t, ctx, requests, viewer)
	if lowWatermark.Reason != "low_watermark" || !slices.Contains(lowWatermark.ExcludedPostIDs, first) || !slices.Contains(lowWatermark.ExcludedPostIDs, second) {
		t.Fatalf("request = %+v", lowWatermark)
	}
}

// The broker is unreachable: the disconnect answers anyway and the event waits in the
// outbox until the relay can publish it.
func TestFeedPrefetchOnBroker_AnUnreachableBrokerDoesNotHoldTheDisconnect(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Minute)
	defer cancel()

	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	amqpURI := startBroker(t, ctx)
	db := startReplicaSet(t, ctx)
	// Nothing listens here: every dial fails after the connection is refused.
	app := newPrefetchStack(t, ctx, "amqp://guest:guest@127.0.0.1:1/", db, logger)
	sessions := feedmongo.NewSessionRepository(db)
	userID := uuid.New()

	started := time.Now()
	if err := app.disconnectUsecase(sessions).Execute(ctx, usecase.RegisterUserDisconnectedInput{UserID: userID}); err != nil {
		t.Fatalf("Execute() error = %v", err)
	}
	if elapsed := time.Since(started); elapsed >= 5*time.Second {
		t.Fatalf("the disconnect took %s, as long as a broker confirm timeout", elapsed)
	}
	app.relay.Wait()

	var event outbox.Event
	if err := db.Collection("outbox_events").FindOne(ctx, bson.D{{Key: "event_type", Value: publisher.EventType}}).Decode(&event); err != nil {
		t.Fatalf("FindOne() error = %v", err)
	}
	if event.Status != outbox.StatusPending || event.Attempts != 0 {
		t.Fatalf("event = %+v, want PENDING with no attempt spent", event)
	}

	// The broker is back: the relay's batch publishes it. No queue is bound, so the
	// topology and the verification queue are declared first.
	reachable := &javaEventsApp{amqpURI: amqpURI, db: db}
	declareFeedExchange(t, amqpURI)
	requests := reachable.bindFeedRequests(t, ctx)
	amqpPublisher := outbox.NewAMQPPublisher(amqpURI, logger)
	t.Cleanup(func() { amqpPublisher.Close() })
	relay := outbox.NewRelay(app.store, amqpPublisher, outbox.DefaultRelayConfig(), logger)
	if err := relay.RelayBatch(ctx); err != nil {
		t.Fatalf("RelayBatch() error = %v", err)
	}

	message := nextFeedRequest(t, ctx, requests, userID.String())
	if message.Reason != "disconnect" {
		t.Fatalf("message = %+v", message)
	}
}

func declareFeedExchange(t *testing.T, amqpURI string) {
	t.Helper()

	conn, err := rawamqp.Dial(amqpURI)
	if err != nil {
		t.Fatalf("amqp.Dial() error = %v", err)
	}
	defer conn.Close()
	channel, err := conn.Channel()
	if err != nil {
		t.Fatalf("conn.Channel() error = %v", err)
	}
	defer channel.Close()
	if err := channel.ExchangeDeclare(topology.ExchangeFeedEvents, "topic", true, false, false, false, nil); err != nil {
		t.Fatalf("ExchangeDeclare() error = %v", err)
	}
}
