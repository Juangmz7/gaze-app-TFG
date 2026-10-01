package router_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/ThreeDotsLabs/watermill"
	wmamqp "github.com/ThreeDotsLabs/watermill-amqp/v3/pkg/amqp"
	"github.com/ThreeDotsLabs/watermill/message"
	rawamqp "github.com/rabbitmq/amqp091-go"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/dispatch"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/router"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/topology"
)

// TestRouter_MalformedPayloadIsRoutedToTheDeadLetterQueue proves the DLQ
// topology end to end against a real broker: a handler that classifies a
// payload as permanent (rmqerror.Permanent, the same classification
// postcreated.Handler and its siblings use for malformed/invalid payloads)
// causes the retry middleware to skip retries and the message is nacked
// straight to the queue's dead-letter queue, not requeued onto the main
// queue and not silently dropped.
func TestRouter_MalformedPayloadIsRoutedToTheDeadLetterQueue(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	amqpURI := startRabbitMQ(t, ctx)

	spec := topology.Spec{
		Exchange:     "x.router.dlq.test.events",
		ExchangeType: "topic",
		Queue:        "q.router.dlq.test.queue",
		RoutingKeys:  []string{"rk.router.dlq.test.malformed"},
	}

	handlers := map[string]dispatch.EventHandlerFunc{
		"rk.router.dlq.test.malformed": func(ctx context.Context, msg *message.Message) error {
			return rmqerror.NewPermanent(errors.New("malformed payload: cannot decode"))
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
	wmRouter.AddConsumerHandler("router-dlq-test-consumer", spec.Queue, subscriber, dispatcher.Handle)

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

	malformedPayload := []byte("this is not a valid event payload")
	publishRaw(t, ctx, amqpURI, spec.Exchange, "rk.router.dlq.test.malformed", malformedPayload)

	deadLetterQueue := topology.DLQName(spec.Queue)
	delivery := consumeOneDelivery(t, ctx, amqpURI, deadLetterQueue, 30*time.Second)

	if string(delivery.Body) != string(malformedPayload) {
		t.Fatalf("dead-lettered payload = %q, want %q", string(delivery.Body), string(malformedPayload))
	}

	assertQueueDepth(t, amqpURI, spec.Queue, 0)
}

// consumeOneDelivery consumes a single delivery from queueName using a plain
// amqp091-go channel, independent of the subscriber under test, within
// timeout.
func consumeOneDelivery(t *testing.T, ctx context.Context, amqpURI, queueName string, timeout time.Duration) rawamqp.Delivery {
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

	consumeCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	deliveries, err := channel.ConsumeWithContext(consumeCtx, queueName, "", true, false, false, false, nil)
	if err != nil {
		t.Fatalf("channel.ConsumeWithContext() error = %v", err)
	}

	select {
	case delivery := <-deliveries:
		return delivery
	case <-time.After(timeout):
		t.Fatalf("timed out waiting for a delivery on queue %q", queueName)
	}

	return rawamqp.Delivery{}
}

// assertQueueDepth opens its own connection to assert queueName's message
// count, proving the malformed message is not stuck/requeued on the main
// queue.
func assertQueueDepth(t *testing.T, amqpURI, queueName string, want int) {
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

	queue, err := channel.QueueInspect(queueName)
	if err != nil {
		t.Fatalf("channel.QueueInspect() error = %v", err)
	}
	if queue.Messages != want {
		t.Fatalf("queue %q depth = %d, want %d", queueName, queue.Messages, want)
	}
}
