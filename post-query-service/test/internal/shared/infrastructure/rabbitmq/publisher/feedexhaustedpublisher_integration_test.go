package publisher_test

import (
	"context"
	"encoding/json"
	"log/slog"
	"testing"
	"time"

	"github.com/ThreeDotsLabs/watermill"
	wmamqp "github.com/ThreeDotsLabs/watermill-amqp/v3/pkg/amqp"
	"github.com/google/uuid"
	rawamqp "github.com/rabbitmq/amqp091-go"
	"github.com/testcontainers/testcontainers-go/modules/rabbitmq"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/publisher"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/router"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/topology"
)

// TestPublisher_Publish_RoutesUserFeedExhaustedEventToTheFeedEventsExchange
// proves the publisher against a real broker: the event lands on
// x.feed.events with routing key rk.post.feed.exhausted, independently
// verified by a plain amqp091-go consumer bound to that exact routing key.
func TestPublisher_Publish_RoutesUserFeedExhaustedEventToTheFeedEventsExchange(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

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

	deliveries := declareVerificationQueue(t, ctx, amqpURI, topology.ExchangeFeedEvents, topology.RKFeedExhausted)

	wmLogger := watermill.NewSlogLogger(slog.Default())
	amqpPublisher, err := wmamqp.NewPublisher(router.NewPublisherConfig(amqpURI, topology.ExchangeFeedEvents), wmLogger)
	if err != nil {
		t.Fatalf("wmamqp.NewPublisher() error = %v", err)
	}
	t.Cleanup(func() {
		if closeErr := amqpPublisher.Close(); closeErr != nil {
			t.Logf("amqpPublisher.Close() error = %v", closeErr)
		}
	})

	pub := publisher.NewPublisher(amqpPublisher)

	event := publisher.Event{
		ID:            uuid.New(),
		CorrelationID: uuid.New(),
		OccurredAt:    time.Now().UTC(),
		UserID:        uuid.New(),
	}

	if err := pub.Publish(ctx, event); err != nil {
		t.Fatalf("Publish() error = %v", err)
	}

	select {
	case delivery := <-deliveries:
		var gotEvent publisher.Event
		if err := json.Unmarshal(delivery.Body, &gotEvent); err != nil {
			t.Fatalf("json.Unmarshal() error = %v", err)
		}
		if gotEvent.UserID != event.UserID {
			t.Fatalf("delivered payload UserID = %v, want %v", gotEvent.UserID, event.UserID)
		}
	case <-time.After(20 * time.Second):
		t.Fatal("timed out waiting for the published event on x.feed.events/rk.post.feed.exhausted")
	}
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
