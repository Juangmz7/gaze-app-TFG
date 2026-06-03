package rabbitmqmessaging

import (
	"context"
	"fmt"
	"log/slog"
	"time"
	"sync"

	amqp "github.com/rabbitmq/amqp091-go"
)

// Start runs the consumer in a reconnect loop.
// Call this in a goroutine: go messaging.Start(ctx, cfg.RabbitMQ)
func Start(ctx context.Context, cfg RabbitMQConfig, routerMap map[string] func(bs []QueueBinding, msg amqp.Delivery)) {
    for {
        select {
        case <-ctx.Done():
            slog.Info("[rabbitmq] context cancelled, stopping consumer")
            return
        default:
        }

        slog.Info("[rabbitmq] connecting...")
        err := runConsumer(ctx, cfg, routerMap)
        if err != nil {
            slog.Error("[rabbitmq] consumer error — reconnecting in 5s", "error", err)
        }

        select {
        case <-ctx.Done():
            return
        case <-time.After(5 * time.Second):
        }
    }
}

func runConsumer(
    ctx context.Context,
    cfg RabbitMQConfig,
    routerMap map[string] func(bs []QueueBinding, msg amqp.Delivery),
) error {
    appCtx, cancelApp := context.WithCancel(ctx)
	defer cancelApp()

    conn, err := amqp.Dial(cfg.URL)
    if err != nil {
        return fmt.Errorf("dial: %w", err)
    }
    defer conn.Close()

    err = assertValidConfig(cfg)
    if err != nil {
        return fmt.Errorf("invalid config: %w", err)
    }

    channels := make(map[string]*amqp.Channel, len(cfg.Queues))
    for _, q := range cfg.Queues {
        channels[q.Name], err = conn.Channel()
        if err != nil {
            return fmt.Errorf("channel-%v: %w", q.Name, err)
        }

        defer channels[q.Name].Close()
    }

	err = setupTopology(conn, cfg)
    if err != nil {
        return fmt.Errorf("setup topology: %w", err)
    }

    err = setQueuePrefetch(channels, cfg)
    if err != nil {
        return fmt.Errorf("set queue prefetch: %w", err)
    }

    msgs, err := consumeChannels(channels, cfg)
    if err != nil {
        return err
    }

    for _, b := range cfg.Bindings {
        slog.Info("[rabbitmq] listening on queue '%s' for key: %s",
        b.QueueName, b.RoutingKey)
    }

    // Listener for main connection channel
    connClose := conn.NotifyClose(make(chan *amqp.Error, 1))
    go func() {
        select {
            case <-appCtx.Done():
                return
            case <- connClose:
                slog.Error("Global conection lost! Shutting down workers", "error", err)
                cancelApp()
        }
    }()

    var wg sync.WaitGroup
    for queueName, msg := range msgs {
        wg.Add(1)
        go processMessages(appCtx, &wg, queueName, channels[queueName], msg, cfg.Bindings, routerMap[queueName])
    }
    wg.Wait()

    return nil
}

func processMessages(
	ctx context.Context,
	wg *sync.WaitGroup,
	queueName string,
	ch *amqp.Channel,
	msgs <-chan amqp.Delivery,
	bindings []QueueBinding,
	messageRouterHandler func(bs []QueueBinding, msg amqp.Delivery),
) error {
	defer wg.Done()

	// Channel to listen if RabbitMQ closes this specific worker
	chClose := ch.NotifyClose(make(chan *amqp.Error, 1))

	slog.Info("Worker started and waiting for messages", "queue", queueName)

	for {
		select {
		case <-ctx.Done():
			slog.Info("Stopping worker due to context cancellation", "queue", queueName)
			return nil

		case err := <-chClose:
			if err != nil {
				return fmt.Errorf("channel closed by rabbitmq: %w", err)
			}
			return fmt.Errorf("rabbitmq channel closed unexpectedly")

		case msg, ok := <-msgs:
			if !ok {
				return fmt.Errorf("local message channel closed")
			}

            // Check for context cancellation before processing the message
            select {
                case <-ctx.Done():
                    slog.Info("Stopping worker due to context cancellation", "queue", queueName)
                    return nil
                case err := <-chClose:
                    if err != nil {
                        return fmt.Errorf("channel closed by rabbitmq: %w", err)
                    }
                    return fmt.Errorf("rabbitmq channel closed unexpectedly")
                default:
            }

			// Delegate business logic to the router
			messageRouterHandler(bindings, msg)
		}
	}
}

func consumeChannels(channels map[string]*amqp.Channel, cfg RabbitMQConfig) (map[string]<-chan amqp.Delivery, error) {
    msgsMap := make(map[string]<-chan amqp.Delivery, len(cfg.Queues))
    for _, q := range cfg.Queues {
        ch := channels[q.Name]

        msgs, err := ch.Consume(
            q.Name,
            "",    // consumer tag, auto-generated
            false, // autoAck — always false in production
            false, // exclusive
            false, // no-local
            false, // no-wait
            nil,
        )
        if err != nil {
            return nil, fmt.Errorf("consume channels: %w", err)
        }

        msgsMap[q.Name] = msgs
    }
    return msgsMap, nil
}

func setQueuePrefetch(channels map[string]*amqp.Channel, cfg RabbitMQConfig) error {
	for _, q := range cfg.Queues {
        ch := channels[q.Name]

        err := ch.Qos((int)(q.PrefetchCount), 0, false);
        if err != nil {
            return fmt.Errorf("qos: %w", err)
        }
    }

    return nil
}

func assertValidConfig(cfg RabbitMQConfig) error {
	if len(cfg.Queues) == 0 {
        return fmt.Errorf("Consumer must have at least one queue to consume from")
    }

    if len(cfg.Bindings) == 0 {
        return fmt.Errorf("Consumer must have at least one binding")
    }

    if len(cfg.Bindings) != len(cfg.RoutingKeys) {
        return fmt.Errorf("Consumer must have the same number of bindings and routing keys")
    }
    return nil
}

func setupTopology(conn *amqp.Connection, cfg RabbitMQConfig) error {
    ch, err := conn.Channel()
    if err != nil {
        return fmt.Errorf("temporal channel: %w", err)
    }
    defer ch.Close()

	for _, e := range cfg.Exchanges {
		err = ch.ExchangeDeclare(
			e.Name,
			e.Type,
			e.Durable,
			e.AutoDelete,
			e.Internal,
			e.NoWait,
			nil,
		)

		if err != nil {
        	return fmt.Errorf("exchange declare: %w", err)
    	}
	}

	for _, q := range cfg.Queues {
        // Declare automatically a DLQ for each queue
        dlqName := q.Name + "-dlq"
        _, err := ch.QueueDeclare(
			dlqName,
			q.Durable,
			q.AutoDelete,
			q.Exclusive,
			q.NoWait,
            nil,
		)

        args := amqp.Table{
            "x-dead-letter-exchange":    "",
            "x-dead-letter-routing-key": dlqName,
        }
            
		_, err = ch.QueueDeclare(
			q.Name,
			q.Durable,
			q.AutoDelete,
			q.Exclusive,
			q.NoWait,
			args,
		)
		if err != nil {
			return fmt.Errorf("queue declare: %w", err)
		}
	}

    for _, b := range cfg.Bindings {
        err = ch.QueueBind(b.QueueName, b.RoutingKey, b.ExchangeName, b.NoWait, nil);
		
		if err != nil {
            return fmt.Errorf("queue bind (%s): %w", b.RoutingKey, err)
        }
    }

    return nil
}