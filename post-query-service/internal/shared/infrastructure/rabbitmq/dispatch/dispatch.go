// Package dispatch routes a single AMQP queue's deliveries to the correct
// per-event-type handler based on the original routing key, since one
// Watermill queue subscription receives every routing key bound to it (see
// topology.Spec).
package dispatch

import (
	"context"
	"log/slog"

	"github.com/ThreeDotsLabs/watermill/message"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/topology"
)

// EventHandlerFunc handles one event type's payload. Implementations own
// decoding, envelope validation, idempotency checking, and use case
// delegation for that single event type.
type EventHandlerFunc func(ctx context.Context, msg *message.Message) error

// Dispatcher routes messages consumed from one queue to the EventHandlerFunc
// registered for the delivery's routing key.
type Dispatcher struct {
	queue    string
	handlers map[string]EventHandlerFunc
	logger   *slog.Logger
}

// New creates a Dispatcher for queue. handlers maps routing key to the
// EventHandlerFunc responsible for it; a routing key bound to the queue but
// missing from handlers is logged and acknowledged (no-op), not treated as
// an error, since the binding itself is still valid topology.
func New(queue string, handlers map[string]EventHandlerFunc, logger *slog.Logger) *Dispatcher {
	return &Dispatcher{queue: queue, handlers: handlers, logger: logger}
}

// Handle implements watermill message.NoPublishHandlerFunc. It reads the
// routing key injected by the subscriber marshaler (topology.RoutingKeyHeader)
// and delegates to the matching EventHandlerFunc.
func (d *Dispatcher) Handle(msg *message.Message) error {
	ctx := msg.Context()
	routingKey := msg.Metadata.Get(topology.RoutingKeyHeader)

	handler, ok := d.handlers[routingKey]
	if !ok {
		d.logger.WarnContext(ctx, "discarding event: no handler registered for routing key",
			"queue", d.queue, "routing_key", routingKey)
		return nil
	}

	return handler(ctx, msg)
}
