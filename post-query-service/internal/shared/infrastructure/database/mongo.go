// Package database wires the MongoDB client and database handle used by
// post-query-service repositories. It owns connect/ping/disconnect lifecycle
// so that callers only need to inject the resulting *mongo.Database.
package database

import (
	"context"
	"fmt"
	"strings"
	"time"

	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"
)

// connectTimeout bounds how long the initial connection ping may take.
const connectTimeout = 10 * time.Second

// disconnectTimeout bounds how long a graceful disconnect may take.
const disconnectTimeout = 5 * time.Second

// Connect creates a MongoDB client for uri, verifies connectivity with Ping,
// and returns both the client (for lifecycle management, e.g. Disconnect)
// and the named database handle (the injection point for repositories).
// The returned client must be closed with Disconnect once the caller is done.
func Connect(ctx context.Context, uri, databaseName string) (*mongo.Client, *mongo.Database, error) {
	if strings.TrimSpace(uri) == "" {
		return nil, nil, fmt.Errorf("connect mongo: uri is required")
	}
	if strings.TrimSpace(databaseName) == "" {
		return nil, nil, fmt.Errorf("connect mongo: database name is required")
	}

	client, err := mongo.Connect(options.Client().ApplyURI(uri))
	if err != nil {
		return nil, nil, fmt.Errorf("connect mongo: %w", err)
	}

	pingCtx, cancel := context.WithTimeout(ctx, connectTimeout)
	defer cancel()

	if err := client.Ping(pingCtx, nil); err != nil {
		_ = Disconnect(client)
		return nil, nil, fmt.Errorf("connect mongo: ping failed: %w", err)
	}

	return client, client.Database(databaseName), nil
}

// Disconnect closes client using its own bounded timeout context, independent
// of any cancelled shutdown context, so the driver has time to flush cleanly.
func Disconnect(client *mongo.Client) error {
	if client == nil {
		return nil
	}

	ctx, cancel := context.WithTimeout(context.Background(), disconnectTimeout)
	defer cancel()

	if err := client.Disconnect(ctx); err != nil {
		return fmt.Errorf("disconnect mongo: %w", err)
	}

	return nil
}
