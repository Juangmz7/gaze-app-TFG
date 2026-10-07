package topology

import (
	"fmt"

	"github.com/ThreeDotsLabs/watermill"
	wmamqp "github.com/ThreeDotsLabs/watermill-amqp/v3/pkg/amqp"
	rawamqp "github.com/rabbitmq/amqp091-go"
)

// Builder declares the exchange, queue, dead-letter wiring, and every
// routing-key binding described by Spec. It implements
// github.com/ThreeDotsLabs/watermill-amqp/v3/pkg/amqp.TopologyBuilder.
//
// The default builder shipped with watermill-amqp only binds a queue to a
// single routing key derived from the Watermill "topic" string, which cannot
// express "one queue, many routing keys" (see technical_constraints in
// feature_list.json task 41). Builder declares the full topology itself,
// including a dedicated dead-letter exchange/queue per queue, matching the
// "{exchange}.dlx" / "{queue}.dlq" / "{queue}.fall-back" pattern used by
// post-command-service.
type Builder struct {
	Spec Spec
}

// ExchangeDeclare declares a durable topic exchange named exchangeName. It is
// called by watermill-amqp when only the exchange (not the full queue
// topology) needs to exist, e.g. from a publisher.
func (b *Builder) ExchangeDeclare(channel *rawamqp.Channel, exchangeName string, _ wmamqp.Config) error {
	return declareTopicExchange(channel, exchangeName)
}

// BuildTopology declares b.Spec's exchange, dead-letter exchange, dead-letter
// queue, main queue (wired to the dead-letter exchange), and every
// routing-key binding from the main exchange to the main queue.
func (b *Builder) BuildTopology(channel *rawamqp.Channel, _ wmamqp.BuildTopologyParams, _ wmamqp.Config, _ watermill.LoggerAdapter) error {
	if err := declareTopicExchange(channel, b.Spec.Exchange); err != nil {
		return err
	}

	dlx := DLXName(b.Spec.Exchange)
	if err := channel.ExchangeDeclare(dlx, "direct", true, false, false, false, nil); err != nil {
		return fmt.Errorf("declare dead-letter exchange %q: %w", dlx, err)
	}

	dlq := DLQName(b.Spec.Queue)
	if _, err := channel.QueueDeclare(dlq, true, false, false, false, nil); err != nil {
		return fmt.Errorf("declare dead-letter queue %q: %w", dlq, err)
	}

	fallbackKey := FallbackRoutingKey(b.Spec.Queue)
	if err := channel.QueueBind(dlq, fallbackKey, dlx, false, nil); err != nil {
		return fmt.Errorf("bind dead-letter queue %q to %q: %w", dlq, dlx, err)
	}

	queueArgs := rawamqp.Table{
		"x-dead-letter-exchange":    dlx,
		"x-dead-letter-routing-key": fallbackKey,
	}
	if _, err := channel.QueueDeclare(b.Spec.Queue, true, false, false, false, queueArgs); err != nil {
		return fmt.Errorf("declare queue %q: %w", b.Spec.Queue, err)
	}

	for _, routingKey := range b.Spec.RoutingKeys {
		if err := channel.QueueBind(b.Spec.Queue, routingKey, b.Spec.Exchange, false, nil); err != nil {
			return fmt.Errorf("bind queue %q to %q with routing key %q: %w", b.Spec.Queue, b.Spec.Exchange, routingKey, err)
		}
	}

	return nil
}

func declareTopicExchange(channel *rawamqp.Channel, exchangeName string) error {
	if err := channel.ExchangeDeclare(exchangeName, "topic", true, false, false, false, nil); err != nil {
		return fmt.Errorf("declare exchange %q: %w", exchangeName, err)
	}

	return nil
}
