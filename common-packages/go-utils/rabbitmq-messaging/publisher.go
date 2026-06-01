package rabbitmqmessaging

import (
	"context"
	"fmt"
	"log/slog"
	"math/rand"
	"time"

	"github.com/Juangmz7/TFG/common-packages/go-utils/common"
	amqp "github.com/rabbitmq/amqp091-go"
)

type MessageBrokerRetryConfig struct {
	MaxRetries int
	Backoff    time.Duration
}

type poolChannel struct {
	ch     *amqp.Channel
	closed chan *amqp.Error
}

type Publisher struct {
	conn        *amqp.Connection
	chPool      chan *poolChannel
	retryConfig *MessageBrokerRetryConfig
}

type Message struct {
	RoutingKey string
	Exchange   string
	Body       any
}

func NewPublisher(cfg RabbitMQConfig, poolSize int, retryConfig *MessageBrokerRetryConfig) (*Publisher, error) {
	if poolSize < 1 {
		poolSize = 1
	}
	if retryConfig == nil {
		retryConfig = &MessageBrokerRetryConfig{
			MaxRetries: 1,
			Backoff:    100 * time.Millisecond,
		}
	}
	if retryConfig.MaxRetries < 1 {
		retryConfig.MaxRetries = 1
	}
	if retryConfig.Backoff < 1 {
		retryConfig.Backoff = 100 * time.Millisecond
	}

	conn, err := amqp.Dial(cfg.URL)
	if err != nil {
		return nil, fmt.Errorf("publisher dial: %w", err)
	}
	
	chPool := make(chan *poolChannel, poolSize)
	for i := 0; i < poolSize; i++ {
		pCh, err := createPoolChannel(conn)
		if err != nil {
			conn.Close()
			return nil, fmt.Errorf("failed to initialize publisher channel pool %v: %w", i, err)
		}
		chPool <- pCh
	}

	return &Publisher{
		conn:        conn,
		chPool:      chPool,
		retryConfig: retryConfig,
	}, nil
}

func (p *Publisher) Publish(ctx context.Context, m *Message) error {
	body, err := common.ParseAnyToBytes(m.Body)
	if err != nil {
		return err
	}

	var lastErr error

	for attempt := 1; attempt <= p.retryConfig.MaxRetries; attempt++ {
		lastErr = p.executePublishAttempt(ctx, m, body)
		if lastErr == nil {
			slog.Info("sent message from publisher", "routingKey", m.RoutingKey)
			return nil
		}

		slog.Warn("publish attempt failed, retrying...", "attempt", attempt, "error", lastErr)

		if attempt < p.retryConfig.MaxRetries {
			time.Sleep(p.calculateBackoff(attempt))
		}
	}

	return fmt.Errorf("publish failed after %d attempts: %w", p.retryConfig.MaxRetries, lastErr)
}

func (p *Publisher) executePublishAttempt(ctx context.Context, m *Message, body []byte) error {
	pCh := <-p.chPool
	chIsDead := false

	defer func() {
		p.returnChannelToPool(pCh, chIsDead)
	}()

	var err error
	pCh, err = p.getHealthyChannel(pCh)
	if err != nil {
		return err
	}

	err = pCh.ch.PublishWithContext(
		ctx,
		m.Exchange,
		m.RoutingKey,
		false,
		false,
		amqp.Publishing{
			ContentType:  "application/json",
			Body:         body,
			DeliveryMode: amqp.Persistent,
		},
	)
	if err != nil {
		chIsDead = true
		return err
	}

	return nil
}

// getHealthyChannel inspects if the channel died in standby and fixes it.
func (p *Publisher) getHealthyChannel(pCh *poolChannel) (*poolChannel, error) {
	select {
	case err := <-pCh.closed:
		slog.Warn("detected dead channel in pool before publishing, forcing replacement", "error", err)
		return createPoolChannel(p.conn)
	default:
		return pCh, nil
	}
}

// returnChannelToPool handles resource recycling or discarding transparently.
func (p *Publisher) returnChannelToPool(pCh *poolChannel, isDead bool) {
	if isDead {
		newPCh, err := createPoolChannel(p.conn)
		if err != nil {
			slog.Error("failed to replace dead channel in pool", "error", err)
			return
		}
		p.chPool <- newPCh
		slog.Info("replaced dead channel in the publisher pool with a fresh one")
	} else {
		p.chPool <- pCh
	}
}

// calculateBackoff handles the mathematical exponential distribution with jitter.
func (p *Publisher) calculateBackoff(attempt int) time.Duration {
	exponentialFactor := 1 << (attempt - 1)
	baseBackoff := p.retryConfig.Backoff * time.Duration(exponentialFactor)
	jitter := time.Duration(rand.Intn(40)+10) * time.Millisecond
	return baseBackoff + jitter
}

func createPoolChannel(conn *amqp.Connection) (*poolChannel, error) {
	rawCh, err := conn.Channel()
	if err != nil {
		return nil, err
	}
	closeChan := rawCh.NotifyClose(make(chan *amqp.Error, 1))
	return &poolChannel{ch: rawCh, closed: closeChan}, nil
}