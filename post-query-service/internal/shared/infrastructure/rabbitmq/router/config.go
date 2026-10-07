// Package router assembles the Watermill router, AMQP subscriber
// configuration, and retry middleware shared by every RabbitMQ consumer in
// post-query-service.
package router

import (
	wmamqp "github.com/ThreeDotsLabs/watermill-amqp/v3/pkg/amqp"
	rawamqp "github.com/rabbitmq/amqp091-go"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/topology"
)

// prefetchCount bounds how many unacknowledged deliveries the broker sends a
// consumer at once, matching post-command-service's listener container
// concurrency expectations without unbounded prefetch.
const prefetchCount = 10

// NewSubscriberConfig builds the amqp.Config for the queue described by
// spec: durable topic exchange/queue, the custom topology.Builder (which
// declares the dead-letter exchange/queue and binds every routing key in
// spec.RoutingKeys), bounded prefetch, and a marshaler that copies the AMQP
// delivery's routing key into topology.RoutingKeyHeader so dispatch.Dispatcher
// can tell which event type arrived.
//
// Consume.NoRequeueOnNack is true: a Nack (returned handler error, including
// after retries are exhausted) sends the message to the queue's dead-letter
// exchange instead of requeueing it back onto the same queue forever.
func NewSubscriberConfig(amqpURI string, spec topology.Spec) wmamqp.Config {
	cfg := wmamqp.NewDurableTopicConfig(amqpURI, spec.Exchange, spec.Queue)

	cfg.Marshaler = wmamqp.DefaultMarshaler{
		PreprocessDelivery: func(delivery rawamqp.Delivery) rawamqp.Delivery {
			if delivery.Headers == nil {
				delivery.Headers = rawamqp.Table{}
			}
			delivery.Headers[topology.RoutingKeyHeader] = delivery.RoutingKey
			return delivery
		},
	}

	cfg.Consume.NoRequeueOnNack = true
	cfg.Consume.Qos.PrefetchCount = prefetchCount

	cfg.TopologyBuilder = &topology.Builder{Spec: spec}

	return cfg
}
