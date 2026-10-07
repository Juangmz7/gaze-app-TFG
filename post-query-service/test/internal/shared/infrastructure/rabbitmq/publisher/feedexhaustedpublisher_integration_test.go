package publisher_test

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"testing"
	"time"

	"github.com/google/uuid"
	rawamqp "github.com/rabbitmq/amqp091-go"
	"github.com/testcontainers/testcontainers-go/modules/mongodb"
	"github.com/testcontainers/testcontainers-go/modules/rabbitmq"
	mongodriver "go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/database"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/outbox"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/publisher"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/topology"
)

// TestPublisher_Publish_IsRelayedToTheFeedEventsExchange proves the full path
// against real MongoDB (replica set) and RabbitMQ: the event is stored in the
// outbox inside a transaction, the relay publishes it to x.feed.events with
// routing key rk.post.feed.exhausted, and a plain amqp091-go consumer receives
// the camelCase payload recommendation-service expects.
func TestPublisher_Publish_IsRelayedToTheFeedEventsExchange(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 120*time.Second)
	defer cancel()

	client := startReplicaSetMongo(t, ctx)
	amqpURI := startRabbitMQ(t, ctx)
	deliveries := declareVerificationQueue(t, ctx, amqpURI, topology.ExchangeFeedEvents, topology.RKFeedExhausted)

	store := outbox.NewStore(client.Database("feed_exhausted_publisher_test"))
	if err := store.EnsureIndexes(ctx); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}

	event := publisher.Event{
		ID:            uuid.New(),
		CorrelationID: uuid.New(),
		OccurredAt:    time.Now().UTC(),
		UserID:        uuid.New(),
	}

	err := database.WithTransaction(ctx, client, func(ctx context.Context) error {
		return publisher.NewPublisher(store).Publish(ctx, event)
	})
	if err != nil {
		t.Fatalf("Publish() in transaction error = %v", err)
	}

	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	amqpPublisher := outbox.NewAMQPPublisher(amqpURI, logger)
	t.Cleanup(func() { amqpPublisher.Close() })

	if err := outbox.NewRelay(store, amqpPublisher, outbox.DefaultRelayConfig(), logger).RelayBatch(ctx); err != nil {
		t.Fatalf("RelayBatch() error = %v", err)
	}

	select {
	case delivery := <-deliveries:
		if delivery.MessageId != event.ID.String() {
			t.Fatalf("MessageId = %q, want the event id %q", delivery.MessageId, event.ID)
		}
		var payload map[string]any
		if err := json.Unmarshal(delivery.Body, &payload); err != nil {
			t.Fatalf("json.Unmarshal() error = %v", err)
		}
		if payload["userId"] != event.UserID.String() || payload["correlationId"] != event.CorrelationID.String() {
			t.Fatalf("delivered payload = %v, want camelCase userId/correlationId of %+v", payload, event)
		}
	case <-time.After(20 * time.Second):
		t.Fatal("timed out waiting for the event on x.feed.events/rk.post.feed.exhausted")
	}
}

// startReplicaSetMongo starts a single-node replica set (transactions need
// one) and connects directly to it.
func startReplicaSetMongo(t *testing.T, ctx context.Context) *mongodriver.Client {
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

	return client
}

func startRabbitMQ(t *testing.T, ctx context.Context) string {
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

// declareVerificationQueue declares its own exclusive queue bound to
// exchange/routingKey using a plain amqp091-go channel, independent of the
// publisher under test, and returns a channel of deliveries received on it.
func declareVerificationQueue(t *testing.T, ctx context.Context, amqpURI, exchange, routingKey string) <-chan rawamqp.Delivery {
	t.Helper()

	conn, err := rawamqp.Dial(amqpURI)
	if err != nil {
		t.Fatalf("amqp.Dial() error = %v", err)
	}
	t.Cleanup(func() { conn.Close() })

	channel, err := conn.Channel()
	if err != nil {
		t.Fatalf("conn.Channel() error = %v", err)
	}
	t.Cleanup(func() { channel.Close() })

	if err := channel.ExchangeDeclare(exchange, "topic", true, false, false, false, nil); err != nil {
		t.Fatalf("channel.ExchangeDeclare() error = %v", err)
	}

	queue, err := channel.QueueDeclare("", false, true, true, false, nil)
	if err != nil {
		t.Fatalf("channel.QueueDeclare() error = %v", err)
	}

	if err := channel.QueueBind(queue.Name, routingKey, exchange, false, nil); err != nil {
		t.Fatalf("channel.QueueBind() error = %v", err)
	}

	deliveries, err := channel.ConsumeWithContext(ctx, queue.Name, "", true, true, false, false, nil)
	if err != nil {
		t.Fatalf("channel.ConsumeWithContext() error = %v", err)
	}

	return deliveries
}
