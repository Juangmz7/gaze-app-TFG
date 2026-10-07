package outbox_test

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	rawamqp "github.com/rabbitmq/amqp091-go"
	"go.mongodb.org/mongo-driver/v2/bson"
	mongodriver "go.mongodb.org/mongo-driver/v2/mongo"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/database"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/outbox"
)

const (
	testExchange   = "x.outbox.test"
	testRoutingKey = "rk.outbox.test"
)

func quietLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

func newStore(t *testing.T, ctx context.Context, db *mongodriver.Database) *outbox.Store {
	t.Helper()
	store := outbox.NewStore(db)
	if err := store.EnsureIndexes(ctx); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}
	return store
}

func addEvent(t *testing.T, ctx context.Context, store *outbox.Store, createdAt time.Time) outbox.Event {
	t.Helper()
	event, err := outbox.NewPendingEvent(uuid.New(), uuid.New(), "TestEvent", testExchange, testRoutingKey,
		map[string]string{"hello": "world"})
	if err != nil {
		t.Fatalf("NewPendingEvent() error = %v", err)
	}
	event.CreatedAt = createdAt
	if err := store.Add(ctx, event); err != nil {
		t.Fatalf("Add() error = %v", err)
	}
	return event
}

func findEvent(t *testing.T, ctx context.Context, db *mongodriver.Database, id uuid.UUID) outbox.Event {
	t.Helper()
	var event outbox.Event
	if err := db.Collection("outbox_events").FindOne(ctx, bson.D{{Key: "_id", Value: id}}).Decode(&event); err != nil {
		t.Fatalf("FindOne(%v) error = %v", id, err)
	}
	return event
}

func TestStore_ClaimNext_ClaimsOldestFirstAndReclaimsOnlyExpiredLocks(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 120*time.Second)
	defer cancel()

	db := startReplicaSetMongo(t, ctx).Database("outbox_claim_test")
	store := newStore(t, ctx, db)

	now := time.Now().UTC()
	newer := addEvent(t, ctx, store, now)
	older := addEvent(t, ctx, store, now.Add(-time.Minute))

	claimed, err := store.ClaimNext(ctx, time.Minute)
	if err != nil {
		t.Fatalf("ClaimNext() error = %v", err)
	}
	if claimed == nil || claimed.ID != older.ID {
		t.Fatalf("ClaimNext() = %v, want the oldest event %v", claimed, older.ID)
	}
	if claimed.Status != outbox.StatusProcessing || claimed.Attempts != 1 || claimed.LockedAt == nil {
		t.Fatalf("claimed = %+v, want PROCESSING with attempts=1 and a lock", claimed)
	}

	second, err := store.ClaimNext(ctx, time.Minute)
	if err != nil || second == nil || second.ID != newer.ID {
		t.Fatalf("second ClaimNext() = %v, %v; want %v (locked event must not be reclaimed)", second, err, newer.ID)
	}

	none, err := store.ClaimNext(ctx, time.Minute)
	if err != nil || none != nil {
		t.Fatalf("third ClaimNext() = %v, %v; want nothing claimable", none, err)
	}

	// A relay that died mid-publish: its lock expires and the event is reclaimed
	time.Sleep(50 * time.Millisecond)
	reclaimed, err := store.ClaimNext(ctx, 10*time.Millisecond)
	if err != nil || reclaimed == nil || reclaimed.ID != older.ID || reclaimed.Attempts != 2 {
		t.Fatalf("ClaimNext() after lock expiry = %+v, %v; want %v with attempts=2", reclaimed, err, older.ID)
	}
}

func TestStore_ClaimNext_ConcurrentRelaysClaimEachEventExactlyOnce(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 120*time.Second)
	defer cancel()

	db := startReplicaSetMongo(t, ctx).Database("outbox_concurrency_test")
	store := newStore(t, ctx, db)

	const events = 200
	base := time.Now().UTC()
	for i := 0; i < events; i++ {
		addEvent(t, ctx, store, base.Add(time.Duration(i)*time.Millisecond))
	}

	var (
		mu      sync.Mutex
		claimed = map[uuid.UUID]int{}
		wg      sync.WaitGroup
	)
	for worker := 0; worker < 4; worker++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for {
				event, err := store.ClaimNext(ctx, time.Minute)
				if err != nil {
					t.Errorf("ClaimNext() error = %v", err)
					return
				}
				if event == nil {
					return
				}
				mu.Lock()
				claimed[event.ID]++
				mu.Unlock()
				if err := store.MarkProcessed(ctx, event.ID); err != nil {
					t.Errorf("MarkProcessed() error = %v", err)
					return
				}
			}
		}()
	}
	wg.Wait()

	if len(claimed) != events {
		t.Fatalf("claimed %d distinct events, want %d", len(claimed), events)
	}
	for id, count := range claimed {
		if count != 1 {
			t.Fatalf("event %v claimed %d times, want exactly once", id, count)
		}
	}
}

func TestStore_EnsureIndexes_ExpiresOnlyProcessedEvents(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 120*time.Second)
	defer cancel()

	db := startReplicaSetMongo(t, ctx).Database("outbox_index_test")
	newStore(t, ctx, db)

	cursor, err := db.Collection("outbox_events").Indexes().List(ctx)
	if err != nil {
		t.Fatalf("Indexes().List() error = %v", err)
	}
	var indexes []bson.M
	if err := cursor.All(ctx, &indexes); err != nil {
		t.Fatalf("cursor.All() error = %v", err)
	}

	for _, index := range indexes {
		if index["name"] == "processed_at_ttl" {
			if index["expireAfterSeconds"] == nil {
				t.Fatal("processed_at_ttl has no expireAfterSeconds")
			}
			return
		}
	}
	t.Fatalf("indexes = %v, want a processed_at_ttl TTL index", indexes)
}

func TestWithTransaction_StateAndOutboxEventCommitOrAbortTogether(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 120*time.Second)
	defer cancel()

	client := startReplicaSetMongo(t, ctx)
	db := client.Database("outbox_tx_test")
	store := newStore(t, ctx, db)
	projections := db.Collection("projections")
	// Collections must exist before they are written inside a transaction on older servers
	if err := db.CreateCollection(ctx, "projections"); err != nil {
		t.Fatalf("CreateCollection() error = %v", err)
	}

	write := func(fail bool) (uuid.UUID, error) {
		event, _ := outbox.NewPendingEvent(uuid.New(), uuid.New(), "TestEvent", testExchange, testRoutingKey, "payload")
		return event.ID, database.WithTransaction(ctx, client, func(ctx context.Context) error {
			if _, err := projections.InsertOne(ctx, bson.D{{Key: "_id", Value: event.ID}}); err != nil {
				return err
			}
			if err := store.Add(ctx, event); err != nil {
				return err
			}
			if fail {
				return errors.New("forced failure before commit")
			}
			return nil
		})
	}

	abortedID, err := write(true)
	if err == nil {
		t.Fatal("WithTransaction() error = nil, want the forced failure")
	}
	for _, collection := range []string{"projections", "outbox_events"} {
		count, _ := db.Collection(collection).CountDocuments(ctx, bson.D{{Key: "_id", Value: abortedID}})
		if count != 0 {
			t.Fatalf("%s has the aborted document, want neither state nor event", collection)
		}
	}

	committedID, err := write(false)
	if err != nil {
		t.Fatalf("WithTransaction() error = %v", err)
	}
	for _, collection := range []string{"projections", "outbox_events"} {
		count, _ := db.Collection(collection).CountDocuments(ctx, bson.D{{Key: "_id", Value: committedID}})
		if count != 1 {
			t.Fatalf("%s is missing the committed document, want state and event", collection)
		}
	}
}

func TestRelay_PublishesWithBrokerConfirmAndMarksProcessed(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 120*time.Second)
	defer cancel()

	db := startReplicaSetMongo(t, ctx).Database("outbox_relay_test")
	amqpURI := startRabbitMQ(t, ctx)
	store := newStore(t, ctx, db)
	deliveries := bindVerificationQueue(t, ctx, amqpURI, testExchange, testRoutingKey)

	publisher := outbox.NewAMQPPublisher(amqpURI, quietLogger())
	t.Cleanup(func() { publisher.Close() })

	event := addEvent(t, ctx, store, time.Now().UTC())

	if err := outbox.NewRelay(store, publisher, outbox.DefaultRelayConfig(), quietLogger()).RelayBatch(ctx); err != nil {
		t.Fatalf("RelayBatch() error = %v", err)
	}

	stored := findEvent(t, ctx, db, event.ID)
	if stored.Status != outbox.StatusProcessed || stored.ProcessedAt == nil || stored.LockedAt != nil {
		t.Fatalf("stored = %+v, want PROCESSED with processed_at and no lock", stored)
	}

	select {
	case delivery := <-deliveries:
		assertDelivery(t, delivery, event)
	case <-time.After(20 * time.Second):
		t.Fatal("timed out waiting for the relayed event")
	}
}

func TestAMQPPublisher_TreatsUnroutableMessageAsPublished(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 120*time.Second)
	defer cancel()

	amqpURI := startRabbitMQ(t, ctx)
	if err := openChannel(t, amqpURI).ExchangeDeclare(testExchange, "topic", true, false, false, false, nil); err != nil {
		t.Fatalf("ExchangeDeclare() error = %v", err)
	}

	publisher := outbox.NewAMQPPublisher(amqpURI, quietLogger())
	t.Cleanup(func() { publisher.Close() })

	event, _ := outbox.NewPendingEvent(uuid.New(), uuid.New(), "TestEvent", testExchange, "rk.nobody.listens", "payload")
	if err := publisher.Publish(ctx, event); err != nil {
		t.Fatalf("Publish() unroutable error = %v, want nil (acked + returned is not retried)", err)
	}
}

func TestAMQPPublisher_FailsForUnknownExchangeAndRecoversOnNextPublish(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 120*time.Second)
	defer cancel()

	amqpURI := startRabbitMQ(t, ctx)
	deliveries := bindVerificationQueue(t, ctx, amqpURI, testExchange, testRoutingKey)

	publisher := outbox.NewAMQPPublisher(amqpURI, quietLogger())
	t.Cleanup(func() { publisher.Close() })

	missing, _ := outbox.NewPendingEvent(uuid.New(), uuid.New(), "TestEvent", "x.does-not-exist", testRoutingKey, "payload")
	if err := publisher.Publish(ctx, missing); err == nil {
		t.Fatal("Publish() to an unknown exchange error = nil, want an error")
	}

	ok, _ := outbox.NewPendingEvent(uuid.New(), uuid.New(), "TestEvent", testExchange, testRoutingKey, "payload")
	if err := publisher.Publish(ctx, ok); err != nil {
		t.Fatalf("Publish() after a channel error = %v, want the publisher to reconnect", err)
	}

	select {
	case delivery := <-deliveries:
		assertDelivery(t, delivery, ok)
	case <-time.After(20 * time.Second):
		t.Fatal("timed out waiting for the event published after recovery")
	}
}

func assertDelivery(t *testing.T, delivery rawamqp.Delivery, event outbox.Event) {
	t.Helper()
	if delivery.MessageId != event.ID.String() {
		t.Fatalf("MessageId = %q, want %q", delivery.MessageId, event.ID)
	}
	if delivery.CorrelationId != event.CorrelationID.String() {
		t.Fatalf("CorrelationId = %q, want %q", delivery.CorrelationId, event.CorrelationID)
	}
	if delivery.ContentType != "application/json" {
		t.Fatalf("ContentType = %q, want application/json", delivery.ContentType)
	}
	if delivery.DeliveryMode != rawamqp.Persistent {
		t.Fatalf("DeliveryMode = %d, want persistent", delivery.DeliveryMode)
	}
	if string(delivery.Body) != event.Payload {
		t.Fatalf("Body = %s, want %s", delivery.Body, event.Payload)
	}
}
