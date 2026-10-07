package outbox

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"sync"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"
)

// defaultConfirmTimeout bounds how long Publish waits for the broker confirm.
const defaultConfirmTimeout = 5 * time.Second

// AMQPPublisher publishes outbox events with publisher confirms over its own
// AMQP connection, (re)dialed lazily. Messages are mandatory: an unroutable
// message is acked by the broker but returned, which is logged and treated
// as published (nothing is bound to consume it, so retrying cannot help).
type AMQPPublisher struct {
	uri            string
	confirmTimeout time.Duration
	logger         *slog.Logger

	mu      sync.Mutex
	conn    *amqp.Connection
	channel *amqp.Channel
	returns chan amqp.Return
}

// NewAMQPPublisher creates an AMQPPublisher for uri. Call Close on shutdown.
func NewAMQPPublisher(uri string, logger *slog.Logger) *AMQPPublisher {
	return &AMQPPublisher{uri: uri, confirmTimeout: defaultConfirmTimeout, logger: logger}
}

// Ready (re)connects if needed and reports whether the broker is reachable.
func (p *AMQPPublisher) Ready(_ context.Context) error {
	p.mu.Lock()
	defer p.mu.Unlock()

	_, err := p.ensureChannel()
	return err
}

// Publish sends event as a persistent JSON message (message id = event id)
// and waits for the broker confirm. Publishes are serialized so a returned
// message is always attributed to the event that caused it.
func (p *AMQPPublisher) Publish(ctx context.Context, event Event) error {
	p.mu.Lock()
	defer p.mu.Unlock()

	channel, err := p.ensureChannel()
	if err != nil {
		return err
	}

	ctx, cancel := context.WithTimeout(ctx, p.confirmTimeout)
	defer cancel()

	confirmation, err := channel.PublishWithDeferredConfirmWithContext(ctx, event.Exchange, event.RoutingKey, true, false, amqp.Publishing{
		ContentType:     "application/json",
		ContentEncoding: "utf-8",
		DeliveryMode:    amqp.Persistent,
		MessageId:       event.ID.String(),
		CorrelationId:   event.CorrelationID.String(),
		Type:            event.EventType,
		Timestamp:       time.Now().UTC(),
		Body:            []byte(event.Payload),
	})
	if err != nil {
		p.reset()
		return fmt.Errorf("publish outbox event %s: %w", event.ID, err)
	}

	acked, err := confirmation.WaitContext(ctx)
	if err != nil {
		p.reset()
		return fmt.Errorf("wait broker confirm for outbox event %s: %w", event.ID, err)
	}
	if !acked {
		// Also reported when the broker closes the channel (e.g. unknown exchange)
		p.reset()
		return fmt.Errorf("broker nacked outbox event %s", event.ID)
	}

	p.logReturned(event)
	return nil
}

// Close closes the publisher's channel and connection.
func (p *AMQPPublisher) Close() error {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.reset()
}

// logReturned drains returns received before the confirm. The broker sends
// basic.return before basic.ack, and the client delivers it first.
func (p *AMQPPublisher) logReturned(event Event) {
	for {
		select {
		case returned := <-p.returns:
			p.logger.Warn("outbox event was unroutable and is marked as processed",
				"id", returned.MessageId, "type", returned.Type, "exchange", returned.Exchange,
				"routing_key", returned.RoutingKey, "reply", returned.ReplyText)
		default:
			return
		}
	}
}

func (p *AMQPPublisher) ensureChannel() (*amqp.Channel, error) {
	if p.channel != nil && !p.channel.IsClosed() && p.conn != nil && !p.conn.IsClosed() {
		return p.channel, nil
	}
	p.reset()

	conn, err := amqp.Dial(p.uri)
	if err != nil {
		return nil, fmt.Errorf("outbox publisher: dial rabbitmq: %w", err)
	}

	channel, err := conn.Channel()
	if err != nil {
		_ = conn.Close()
		return nil, fmt.Errorf("outbox publisher: open channel: %w", err)
	}

	if err := channel.Confirm(false); err != nil {
		_ = conn.Close()
		return nil, fmt.Errorf("outbox publisher: enable confirms: %w", err)
	}

	p.conn = conn
	p.channel = channel
	p.returns = channel.NotifyReturn(make(chan amqp.Return, 16))
	return channel, nil
}

func (p *AMQPPublisher) reset() error {
	var err error
	if p.channel != nil && !p.channel.IsClosed() {
		err = p.channel.Close()
	}
	if p.conn != nil && !p.conn.IsClosed() {
		err = errors.Join(err, p.conn.Close())
	}
	p.channel = nil
	p.conn = nil
	p.returns = nil
	return err
}
