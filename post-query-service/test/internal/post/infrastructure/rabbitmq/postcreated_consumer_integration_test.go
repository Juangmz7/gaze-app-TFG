package rabbitmq_test

import (
	"context"
	"encoding/json"
	"sync"
	"testing"
	"time"

	"github.com/ThreeDotsLabs/watermill"
	wmamqp "github.com/ThreeDotsLabs/watermill-amqp/v3/pkg/amqp"
	"github.com/ThreeDotsLabs/watermill/message"
	rawamqp "github.com/rabbitmq/amqp091-go"
	"github.com/testcontainers/testcontainers-go/modules/mongodb"
	"github.com/testcontainers/testcontainers-go/modules/rabbitmq"
	mongodriver "go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/post/application/usecase"
	postmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/post/infrastructure/mongo"
	postrabbitmq "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/post/infrastructure/rabbitmq"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/dispatch"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/idempotency"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/router"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/topology"
)

// countingPostRepository decorates the real, Mongo-backed postmongo.Repository
// so the test can observe how many times the projection side effect actually
// ran, while the write itself still goes to real MongoDB. Counting calls here
// is justified by the verification doc's "Testing dependency interactions"
// guidance: the number of calls is itself the behavior under test (duplicate
// delivery must not apply the side effect twice).
type countingPostRepository struct {
	delegate *postmongo.Repository

	mu    sync.Mutex
	calls int
	done  chan struct{}
}

func newCountingPostRepository(delegate *postmongo.Repository) *countingPostRepository {
	return &countingPostRepository{delegate: delegate, done: make(chan struct{}, 10)}
}

func (r *countingPostRepository) Insert(ctx context.Context, input usecase.Input) error {
	if err := r.delegate.Insert(ctx, input); err != nil {
		return err
	}

	r.mu.Lock()
	r.calls++
	r.mu.Unlock()
	r.done <- struct{}{}

	return nil
}

func (r *countingPostRepository) callCount() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.calls
}

// TestPostConsumer_DuplicateEventDoesNotApplySideEffectTwice proves the
// canonical idempotency scenario named by
// docs/verification/post-query-service-verification.md end to end, against a
// real broker and real MongoDB: the same PostCreatedEvent delivered twice
// results in exactly one projection write, and both deliveries are
// acknowledged (neither is left on the main queue, neither lands on the
// dead-letter queue).
func TestPostConsumer_DuplicateEventDoesNotApplySideEffectTwice(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()

	amqpURI := startRabbitMQForConsumerTest(t, ctx)
	db := startMongoForConsumerTest(t, ctx)

	idempotencyRepo := idempotency.NewRepository(db)
	if err := idempotencyRepo.EnsureIndexes(ctx); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}

	repository := newCountingPostRepository(postmongo.NewRepository(db))
	uc := usecase.New(repository)
	handler := postrabbitmq.New(idempotencyRepo, uc, testLogger())

	spec := topology.Spec{
		Exchange:     "x.postcreated.duplicate.test",
		ExchangeType: "topic",
		Queue:        "q.postcreated.duplicate.test",
		RoutingKeys:  []string{"rk.post.created"},
	}

	handlers := map[string]dispatch.EventHandlerFunc{"rk.post.created": handler.Handle}
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
	wmRouter.AddConsumerHandler("postcreated-duplicate-test-consumer", spec.Queue, subscriber, dispatcher.Handle)

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

	event := validEvent()
	payload, err := json.Marshal(event)
	if err != nil {
		t.Fatalf("json.Marshal() error = %v", err)
	}

	// Publish the first delivery and wait for it to be fully processed and
	// acked (queue depth back to zero) before publishing the duplicate. This
	// mirrors the real-world duplicate scenario this test names: a
	// redelivery (broker requeue, consumer restart, at-least-once delivery)
	// of an event already successfully processed, rather than two copies
	// racing through the handler concurrently.
	publishRawToExchange(t, ctx, amqpURI, spec.Exchange, "rk.post.created", payload)

	select {
	case <-repository.done:
	case <-time.After(30 * time.Second):
		t.Fatal("timed out waiting for the first delivery to apply the projection")
	}
	waitForQueueDepth(t, ctx, amqpURI, spec.Queue, 0, 30*time.Second)

	publishRawToExchange(t, ctx, amqpURI, spec.Exchange, "rk.post.created", payload)
	waitForQueueDepth(t, ctx, amqpURI, spec.Queue, 0, 30*time.Second)

	if got := repository.callCount(); got != 1 {
		t.Fatalf("repository Insert() calls = %d, want exactly 1 for two deliveries of the same event", got)
	}

	waitForQueueDepth(t, ctx, amqpURI, topology.DLQName(spec.Queue), 0, 10*time.Second)
}

func startRabbitMQForConsumerTest(t *testing.T, ctx context.Context) string {
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

func startMongoForConsumerTest(t *testing.T, ctx context.Context) *mongodriver.Database {
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

	return client.Database("post_query_postcreated_consumer_test")
}

// publishRawToExchange publishes a message directly with amqp091-go,
// bypassing Watermill, matching the pattern used by
// router/router_integration_test.go.
func publishRawToExchange(t *testing.T, ctx context.Context, amqpURI, exchange, routingKey string, body []byte) {
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

// waitForQueueDepth polls queueName's message count (via a plain amqp091-go
// passive queue inspection) until it equals want or the timeout elapses. A
// bounded poll, not a fixed time.Sleep, is used as the synchronization
// mechanism per the service's testing conventions.
func waitForQueueDepth(t *testing.T, ctx context.Context, amqpURI, queueName string, want int, timeout time.Duration) {
	t.Helper()

	conn, err := rawamqp.Dial(amqpURI)
	if err != nil {
		t.Fatalf("amqp.Dial() error = %v", err)
	}
	defer conn.Close()

	deadline := time.Now().Add(timeout)
	ticker := time.NewTicker(200 * time.Millisecond)
	defer ticker.Stop()

	var lastCount int
	var lastErr error
	for {
		// QueueInspect (a passive queue declare) closes its channel on
		// error, so a fresh channel is opened every attempt.
		channel, err := conn.Channel()
		if err != nil {
			t.Fatalf("conn.Channel() error = %v", err)
		}

		queue, inspectErr := channel.QueueInspect(queueName)
		channel.Close()

		if inspectErr == nil {
			lastCount = queue.Messages
			if lastCount == want {
				return
			}
		}
		lastErr = inspectErr

		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for queue %q depth to reach %d (last observed: %d, inspect error: %v)",
				queueName, want, lastCount, lastErr)
		}

		select {
		case <-ticker.C:
		case <-ctx.Done():
			t.Fatalf("context canceled while waiting for queue %q depth", queueName)
		}
	}
}
