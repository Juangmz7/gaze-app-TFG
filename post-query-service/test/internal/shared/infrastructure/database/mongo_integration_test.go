package database_test

import (
	"context"
	"testing"
	"time"

	"github.com/testcontainers/testcontainers-go/modules/mongodb"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/config"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/database"
)

// TestConnect_SucceedsAgainstARealMongoInstanceLoadedFromEnvironment proves
// the full task-40 flow works end to end: environment variables are parsed
// by config.Load, then database.Connect uses them to reach a real MongoDB
// server (started via Testcontainers) and verify it with Ping.
func TestConnect_SucceedsAgainstARealMongoInstanceLoadedFromEnvironment(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

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

	t.Setenv("POST_QUERY_MONGO_URI", connectionString)
	t.Setenv("POST_QUERY_MONGO_DATABASE", "post_query_integration_test")
	t.Setenv("HTTP_PORT", "8080")

	cfg, err := config.Load()
	if err != nil {
		t.Fatalf("config.Load() error = %v", err)
	}

	client, db, err := database.Connect(ctx, cfg.Mongo.URI, cfg.Mongo.Database)
	if err != nil {
		t.Fatalf("database.Connect() error = %v", err)
	}
	t.Cleanup(func() {
		if disconnectErr := database.Disconnect(client); disconnectErr != nil {
			t.Logf("database.Disconnect() error = %v", disconnectErr)
		}
	})

	if db == nil {
		t.Fatal("database.Connect() db = nil, want a usable *mongo.Database")
	}

	if got := db.Name(); got != cfg.Mongo.Database {
		t.Fatalf("db.Name() = %q, want %q", got, cfg.Mongo.Database)
	}

	if err := client.Ping(ctx, nil); err != nil {
		t.Fatalf("client.Ping() error = %v, want the connection to remain healthy", err)
	}
}

// TestDisconnect_ClosesTheClientAfterShutdown proves the graceful-shutdown
// path: once Disconnect returns, the client can no longer serve requests.
func TestDisconnect_ClosesTheClientAfterShutdown(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

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

	client, _, err := database.Connect(ctx, connectionString, "post_query_integration_test")
	if err != nil {
		t.Fatalf("database.Connect() error = %v", err)
	}

	if err := database.Disconnect(client); err != nil {
		t.Fatalf("database.Disconnect() error = %v", err)
	}

	if err := client.Ping(ctx, nil); err == nil {
		t.Fatal("client.Ping() error = nil after Disconnect, want an error because the client is closed")
	}
}
