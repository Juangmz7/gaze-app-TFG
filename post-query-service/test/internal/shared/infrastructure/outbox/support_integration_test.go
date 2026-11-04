package outbox_test

import (
	"context"
	"testing"

	rawamqp "github.com/rabbitmq/amqp091-go"
	"github.com/testcontainers/testcontainers-go/modules/mongodb"
	"github.com/testcontainers/testcontainers-go/modules/rabbitmq"
	mongodriver "go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"
)

// startReplicaSetMongo starts a single-node replica set (transactions need
// one) and returns a client connected directly to it, so the driver does not
// try to resolve the member host the replica set advertises.
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

// startRabbitMQ starts a broker and returns its AMQP URI.
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

// bindVerificationQueue declares exchange and an exclusive queue bound to
// routingKey with a plain amqp091-go channel, independent of the code under
// test, and returns its deliveries.
func bindVerificationQueue(t *testing.T, ctx context.Context, amqpURI, exchange, routingKey string) <-chan rawamqp.Delivery {
	t.Helper()

	channel := openChannel(t, amqpURI)

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

func openChannel(t *testing.T, amqpURI string) *rawamqp.Channel {
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
	return channel
}
