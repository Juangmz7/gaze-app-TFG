package rabbitmqmessaging

import "os"

type Exchange struct {
    Name string
    Type string
    Durable bool
    AutoDelete bool
    Internal bool
    NoWait bool 
}

type Queue struct {
    Name string
    PrefetchCount uint
    Durable bool
    AutoDelete bool
    Exclusive bool
    NoWait bool
}

type RoutingKey struct {
    Key string
}

type QueueBinding struct {
    QueueName string
    RoutingKey string
    ExchangeName string
    NoWait bool
}

type RabbitMQConfig struct {
    URL         string
    Exchanges    []Exchange
    Queues       []Queue
    Bindings     []QueueBinding
    RoutingKeys  []RoutingKey
}

func loadRabbitMQ(
    exchanges []Exchange,
    queues    []Queue,
    rks       []RoutingKey,
    bindings  []QueueBinding, 
) RabbitMQConfig {
    url := os.Getenv("RABBITMQ_URL")

    return RabbitMQConfig {
        URL: url,
        Exchanges: exchanges,
        Queues: queues,
        Bindings: bindings,
        RoutingKeys: rks,
    }
}