package router_test

import (
	"context"
	"testing"
	"time"

	"github.com/ThreeDotsLabs/watermill"
	wmamqp "github.com/ThreeDotsLabs/watermill-amqp/v3/pkg/amqp"
	"github.com/ThreeDotsLabs/watermill/message"
	rawamqp "github.com/rabbitmq/amqp091-go"
	"github.com/testcontainers/testcontainers-go/modules/rabbitmq"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/dispatch"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/router"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/topology"
)

// TestRouter_ConsumesAMessageFromARealBrokerAndRoutesItByRoutingKey proves
// the full consumer topology against a real broker: a queue bound to many
// routing keys (topology.Spec), a single Watermill consumer handler per
// queue, and dispatch.Dispatcher routing the delivery to the handler
// registered for its routing key.
func TestRouter_ConsumesAMessageFromARealBrokerAndRoutesItByRoutingKey(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	amqpURI := startRabbitMQ(t, ctx)

	spec := topology.Spec{
		Exchange:     "x.router.test.events",
		ExchangeType: "topic",
		Queue:        "q.router.test.queue",
		RoutingKeys:  []string{"rk.router.test.created", "rk.router.test.ignored"},
	}

	received := make(chan string, 1)
	handlers := map[string]dispatch.EventHandlerFunc{
		"rk.router.test.created": func(ctx context.Context, msg *message.Message) error {
			received <- string(msg.Payload)
			return nil
		},
	}
	dispatcher := dispatch.New(spec.Queue, handlers, testLogger())

	wmLogger := watermill.NewSlogLogger(testLogger())
	subscriber, err := wmamqp.NewSubscriber(router.NewSubscriberConfig(amqpURI, spec), wmLogger)
	if err != nil {
		t.Fatalf("wmamqp.NewSubscriber() error = %v", err)
	}
	t.Cleanup(func() {
		if closeErr := subscriber.Close(); closeErr != nil {
			t.Logf("subscriber.Close() error = %v", closeErr)
		}
	})

	wmRouter, err := message.NewRouter(message.RouterConfig{}, wmLogger)
	if err != nil {
		t.Fatalf("message.NewRouter() error = %v", err)
	}
	wmRouter.AddMiddleware(router.NewRetryMiddleware(wmLogger).Middleware)
	wmRouter.AddConsumerHandler("router-test-consumer", spec.Queue, subscriber, dispatcher.Handle)

	routerCtx, stopRouter := context.WithCancel(ctx)
	defer stopRouter()

	go func() {
		if runErr := wmRouter.Run(routerCtx); runErr != nil {
			t.Logf("router.Run() error = %v", runErr)
		}
	}()

	select {
	case <-wmRouter.Running():
	case <-time.After(10 * time.Second):
		t.Fatal("timed out waiting for the router to start running")
	}

	publishRaw(t, ctx, amqpURI, spec.Exchange, "rk.router.test.created", []byte("hello-router"))

	select {
	case payload := <-received:
		if payload != "hello-router" {
			t.Fatalf("received payload = %q, want %q", payload, "hello-router")
		}
	case <-time.After(20 * time.Second):
		t.Fatal("timed out waiting for the handler registered for rk.router.test.created to receive the message")
	}
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

// publishRaw publishes a message directly with amqp091-go, bypassing
// Watermill, so the test exercises the subscriber's own topology
// declaration (exchange, queue, bindings) rather than assuming a publisher
// already declared it.
func publishRaw(t *testing.T, ctx context.Context, amqpURI, exchange, routingKey string, body []byte) {
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

	if err := channel.ExchangeDeclare(exchange, "topic", true, false, false, false, nil); err != nil {
		t.Fatalf("channel.ExchangeDeclare() error = %v", err)
	}

	if err := channel.PublishWithContext(ctx, exchange, routingKey, false, false, rawamqp.Publishing{
		Body: body,
	}); err != nil {
		t.Fatalf("channel.PublishWithContext() error = %v", err)
	}
}
