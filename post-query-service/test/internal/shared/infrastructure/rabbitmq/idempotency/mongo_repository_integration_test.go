package idempotency_test

import (
	"context"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/testcontainers/testcontainers-go/modules/mongodb"
	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	mongodriver "go.mongodb.org/mongo-driver/v2/mongo"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/idempotency"
)

// TestRepository_IsProcessed_PersistsAndRetrievesProcessedEvents proves the
// idempotency store itself: a fresh event is reported unprocessed, marking
// it processed persists it in MongoDB, and it is then reported processed.
func TestRepository_IsProcessed_PersistsAndRetrievesProcessedEvents(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := idempotency.NewRepository(db)

	if err := repository.EnsureIndexes(ctx); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}

	eventID := uuid.New()
	correlationID := uuid.New()

	processed, err := repository.IsProcessed(ctx, eventID)
	if err != nil {
		t.Fatalf("IsProcessed() error = %v", err)
	}
	if processed {
		t.Fatal("IsProcessed() = true, want false before the event was marked processed")
	}

	if err := repository.MarkProcessed(ctx, eventID, correlationID, "PostCreatedEvent"); err != nil {
		t.Fatalf("MarkProcessed() error = %v", err)
	}

	processed, err = repository.IsProcessed(ctx, eventID)
	if err != nil {
		t.Fatalf("IsProcessed() error = %v", err)
	}
	if !processed {
		t.Fatal("IsProcessed() = false, want true after MarkProcessed")
	}
}

// TestRepository_MarkProcessed_IsIdempotentUnderConcurrentDuplicateInserts
// proves the unique index on event_id makes MarkProcessed race-safe: marking
// the same event twice concurrently must not return an error from either
// call, AND must leave exactly one document behind. Asserting only "no
// error from either call" is not enough to prove the index is enforced,
// because without a unique index both concurrent inserts would also succeed
// (as two separate documents) and this test would pass anyway. Counting the
// documents for eventID after both calls complete is what actually makes the
// index's enforcement part of the test.
func TestRepository_MarkProcessed_IsIdempotentUnderConcurrentDuplicateInserts(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := idempotency.NewRepository(db)

	if err := repository.EnsureIndexes(ctx); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}

	eventID := uuid.New()
	correlationID := uuid.New()

	errCh := make(chan error, 2)
	for i := 0; i < 2; i++ {
		go func() {
			errCh <- repository.MarkProcessed(ctx, eventID, correlationID, "PostCreatedEvent")
		}()
	}

	for i := 0; i < 2; i++ {
		select {
		case err := <-errCh:
			if err != nil {
				t.Fatalf("MarkProcessed() error = %v, want nil for a duplicate insert", err)
			}
		case <-time.After(10 * time.Second):
			t.Fatal("timed out waiting for concurrent MarkProcessed calls")
		}
	}

	count, err := db.Collection("processed_events").CountDocuments(ctx, bson.D{{Key: "event_id", Value: eventID}})
	if err != nil {
		t.Fatalf("CountDocuments() error = %v", err)
	}
	if count != 1 {
		t.Fatalf("CountDocuments() = %d, want exactly 1 document for event_id %s (the unique index on "+
			"event_id must reject the second concurrent insert)", count, eventID)
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

	return client.Database("post_query_idempotency_test")
}
