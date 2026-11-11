package database

import (
	"context"
	"sync/atomic"

	"go.mongodb.org/mongo-driver/v2/event"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"
)

const poolMeterName = "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/database"

// driverDefaultMaxPoolSize is the driver's limit per server when the URI sets no maxPoolSize.
const driverDefaultMaxPoolSize = 100

var (
	poolStateIdle = metric.WithAttributes(attribute.String("db.client.connection.state", "idle"))
	poolStateUsed = metric.WithAttributes(attribute.String("db.client.connection.state", "used"))
)

// poolMetrics counts the driver's connection pool events and reports them as the
// OpenTelemetry database client connection metrics:
//
//   - db.client.connection.count, by db.client.connection.state (idle, used)
//   - db.client.connection.max
//   - db.client.connection.pending_requests: operations waiting for a connection
//   - db.client.connection.wait_time: how long an operation waited for a connection
//   - db.client.connection.timeouts: operations that got no connection in time
//
// The counts cover every server of the deployment together.
type poolMetrics struct {
	open    atomic.Int64
	used    atomic.Int64
	pending atomic.Int64

	waitTime metric.Float64Histogram
	timeouts metric.Int64Counter
}

// newPoolMonitor returns the monitor to register with options.Client().SetPoolMonitor,
// and the registration to Unregister once the client is closed: a closed client
// must stop being counted.
// It reads the global MeterProvider, so telemetry must be initialised before the
// client is created. maxPoolSize 0 means the pool has no limit and none is reported.
func newPoolMonitor(maxPoolSize uint64) (*event.PoolMonitor, metric.Registration, error) {
	meter := otel.Meter(poolMeterName)
	metrics := &poolMetrics{}

	var err error
	metrics.waitTime, err = meter.Float64Histogram("db.client.connection.wait_time",
		metric.WithUnit("s"),
		metric.WithDescription("Time an operation waited for a connection from the pool."),
		metric.WithExplicitBucketBoundaries(0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10))
	if err != nil {
		return nil, nil, err
	}
	metrics.timeouts, err = meter.Int64Counter("db.client.connection.timeouts",
		metric.WithUnit("{timeout}"),
		metric.WithDescription("Operations that did not get a connection from the pool in time."))
	if err != nil {
		return nil, nil, err
	}

	count, err := meter.Int64ObservableUpDownCounter("db.client.connection.count",
		metric.WithUnit("{connection}"),
		metric.WithDescription("Connections of the pool, by state."))
	if err != nil {
		return nil, nil, err
	}
	pending, err := meter.Int64ObservableUpDownCounter("db.client.connection.pending_requests",
		metric.WithUnit("{request}"),
		metric.WithDescription("Operations waiting for a connection from the pool."))
	if err != nil {
		return nil, nil, err
	}
	maxConnections, err := meter.Int64ObservableUpDownCounter("db.client.connection.max",
		metric.WithUnit("{connection}"),
		metric.WithDescription("Connections the pool may open to one server."))
	if err != nil {
		return nil, nil, err
	}

	registration, err := meter.RegisterCallback(func(_ context.Context, observer metric.Observer) error {
		used := metrics.used.Load()
		// A connection closed while in use lowers open before used: never report less than 0
		idle := max(metrics.open.Load()-used, 0)
		observer.ObserveInt64(count, idle, poolStateIdle)
		observer.ObserveInt64(count, used, poolStateUsed)
		observer.ObserveInt64(pending, max(metrics.pending.Load(), 0))
		if maxPoolSize > 0 {
			observer.ObserveInt64(maxConnections, int64(maxPoolSize))
		}

		return nil
	}, count, pending, maxConnections)
	if err != nil {
		return nil, nil, err
	}

	return &event.PoolMonitor{Event: metrics.handle}, registration, nil
}

func (m *poolMetrics) handle(poolEvent *event.PoolEvent) {
	ctx := context.Background()

	switch poolEvent.Type {
	case event.ConnectionCreated:
		m.open.Add(1)
	case event.ConnectionClosed:
		m.open.Add(-1)
	case event.ConnectionCheckOutStarted:
		m.pending.Add(1)
	case event.ConnectionCheckedOut:
		m.pending.Add(-1)
		m.used.Add(1)
		m.waitTime.Record(ctx, poolEvent.Duration.Seconds())
	case event.ConnectionCheckOutFailed:
		m.pending.Add(-1)
		m.waitTime.Record(ctx, poolEvent.Duration.Seconds())
		if poolEvent.Reason == event.ReasonTimedOut {
			m.timeouts.Add(ctx, 1)
		}
	case event.ConnectionCheckedIn:
		m.used.Add(-1)
	}
}
