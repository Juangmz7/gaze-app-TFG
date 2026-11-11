package database_test

import (
	"context"
	"testing"
	"time"

	"github.com/testcontainers/testcontainers-go/modules/mongodb"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/metric/metricdata"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/database"
)

// connectionCounts returns db.client.connection.count by state and db.client.connection.max.
func connectionCounts(t *testing.T, reader *sdkmetric.ManualReader) (map[string]int64, int64) {
	t.Helper()
	var collected metricdata.ResourceMetrics
	if err := reader.Collect(context.Background(), &collected); err != nil {
		t.Fatalf("Collect() error = %v, want nil", err)
	}

	byState := map[string]int64{}
	var maxConnections int64
	for _, scope := range collected.ScopeMetrics {
		for _, m := range scope.Metrics {
			sum, ok := m.Data.(metricdata.Sum[int64])
			if !ok {
				continue
			}
			for _, point := range sum.DataPoints {
				switch m.Name {
				case "db.client.connection.count":
					state, _ := point.Attributes.Value(attribute.Key("db.client.connection.state"))
					byState[state.AsString()] = point.Value
				case "db.client.connection.max":
					maxConnections = point.Value
				}
			}
		}
	}

	return byState, maxConnections
}

func TestConnect_ReportsTheConnectionPoolAsMetrics(t *testing.T) {
	// Arrange
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	reader := sdkmetric.NewManualReader()
	provider := sdkmetric.NewMeterProvider(sdkmetric.WithReader(reader))
	previous := otel.GetMeterProvider()
	otel.SetMeterProvider(provider)
	t.Cleanup(func() {
		otel.SetMeterProvider(previous)
		_ = provider.Shutdown(context.Background())
	})

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

	// Act: Connect pings, which opens a connection and returns it to the pool
	client, _, err := database.Connect(ctx, connectionString+"/?maxPoolSize=7", "post_query_integration_test")
	if err != nil {
		t.Fatalf("database.Connect() error = %v", err)
	}
	t.Cleanup(func() { _ = database.Disconnect(client) })

	// Assert
	byState, maxConnections := connectionCounts(t, reader)
	if byState["idle"] < 1 {
		t.Fatalf("idle connections = %d, want at least the one the ping returned to the pool", byState["idle"])
	}
	if byState["used"] != 0 {
		t.Fatalf("used connections = %d, want 0 with no operation in flight", byState["used"])
	}
	if maxConnections != 7 {
		t.Fatalf("db.client.connection.max = %d, want the URI's maxPoolSize 7", maxConnections)
	}
}
