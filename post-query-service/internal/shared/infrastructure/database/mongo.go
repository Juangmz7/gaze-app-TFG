// Package database wires the MongoDB client and database handle used by
// post-query-service repositories. It owns connect/ping/disconnect lifecycle
// so that callers only need to inject the resulting *mongo.Database.
package database

import (
	"context"
	"fmt"
	"strings"
	"sync"
	"time"

	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"
	"go.opentelemetry.io/otel/metric"
)

// poolRegistrations holds, per client, the registration of its pool metrics, which
// Disconnect removes.
var poolRegistrations sync.Map

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

	clientOptions := options.Client().ApplyURI(uri)
	maxPoolSize := uint64(driverDefaultMaxPoolSize)
	if clientOptions.MaxPoolSize != nil {
		maxPoolSize = *clientOptions.MaxPoolSize
	}
	poolMonitor, poolRegistration, err := newPoolMonitor(maxPoolSize)
	if err != nil {
		return nil, nil, fmt.Errorf("connect mongo: pool metrics: %w", err)
	}

	client, err := mongo.Connect(clientOptions.SetPoolMonitor(poolMonitor))
	if err != nil {
		_ = poolRegistration.Unregister()
		return nil, nil, fmt.Errorf("connect mongo: %w", err)
	}

	poolRegistrations.Store(client, poolRegistration)

	pingCtx, cancel := context.WithTimeout(ctx, connectTimeout)
	defer cancel()

	if err := client.Ping(pingCtx, nil); err != nil {
		_ = Disconnect(client)
		return nil, nil, fmt.Errorf("connect mongo: ping failed: %w", err)
	}

	return client, client.Database(databaseName), nil
}

// WithTransaction runs fn inside a multi-document transaction (requires a
// replica set). Every write fn makes with the ctx it receives (for example a
// read-model update plus outbox.Store.Add) commits or aborts together; fn
// returning an error aborts the transaction. The driver retries fn on
// transient transaction errors, so fn must be safe to re-run. Callbacks
// registered with AfterCommit run once the commit succeeds, before
// WithTransaction returns.
func WithTransaction(ctx context.Context, client *mongo.Client, fn func(ctx context.Context) error) error {
	session, err := client.StartSession()
	if err != nil {
		return fmt.Errorf("start mongo session: %w", err)
	}
	defer session.EndSession(context.WithoutCancel(ctx))

	hooks := &afterCommitHooks{}
	_, err = session.WithTransaction(context.WithValue(ctx, afterCommitKey{}, hooks), func(ctx context.Context) (any, error) {
		// A retried attempt registers its callbacks again: keep only the last attempt's
		hooks.reset()
		return nil, fn(ctx)
	})
	if err != nil {
		return err
	}

	// ctx, not the session context: the callbacks run outside the finished transaction
	for _, callback := range hooks.drain() {
		callback(ctx)
	}
	return nil
}

// Transactor runs functions inside WithTransaction on a fixed client, so event
// handlers can receive the transaction boundary as a small injected port.
type Transactor struct {
	client *mongo.Client
}

// NewTransactor creates a Transactor backed by client.
func NewTransactor(client *mongo.Client) *Transactor {
	return &Transactor{client: client}
}

// WithTransaction runs fn inside a multi-document transaction. See the
// package-level WithTransaction: fn must be safe to re-run.
func (t *Transactor) WithTransaction(ctx context.Context, fn func(ctx context.Context) error) error {
	if t.client == nil {
		return fmt.Errorf("run mongo transaction: client is required")
	}
	return WithTransaction(ctx, t.client, fn)
}

// afterCommitKey is the context key under which WithTransaction collects
// AfterCommit callbacks.
type afterCommitKey struct{}

// afterCommitHooks holds the callbacks registered during one transaction.
type afterCommitHooks struct {
	mu        sync.Mutex
	callbacks []func(ctx context.Context)
}

func (h *afterCommitHooks) add(callback func(ctx context.Context)) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.callbacks = append(h.callbacks, callback)
}

func (h *afterCommitHooks) reset() {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.callbacks = nil
}

func (h *afterCommitHooks) drain() []func(ctx context.Context) {
	h.mu.Lock()
	defer h.mu.Unlock()
	callbacks := h.callbacks
	h.callbacks = nil
	return callbacks
}

// AfterCommit runs callback once the WithTransaction transaction carried by
// ctx has committed, and never if it aborts. Outside a transaction it runs
// callback right away, because the write it follows is already durable. The
// callback gets a context outside the transaction's session.
func AfterCommit(ctx context.Context, callback func(ctx context.Context)) {
	hooks, ok := ctx.Value(afterCommitKey{}).(*afterCommitHooks)
	if !ok {
		callback(ctx)
		return
	}
	hooks.add(callback)
}

// Disconnect closes client using its own bounded timeout context, independent
// of any cancelled shutdown context, so the driver has time to flush cleanly.
func Disconnect(client *mongo.Client) error {
	if client == nil {
		return nil
	}

	if registration, ok := poolRegistrations.LoadAndDelete(client); ok {
		// The pool is going away: stop reporting it, whatever the disconnect returns
		_ = registration.(metric.Registration).Unregister()
	}

	ctx, cancel := context.WithTimeout(context.Background(), disconnectTimeout)
	defer cancel()

	if err := client.Disconnect(ctx); err != nil {
		return fmt.Errorf("disconnect mongo: %w", err)
	}

	return nil
}
