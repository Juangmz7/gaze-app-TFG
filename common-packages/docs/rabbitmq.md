# RabbitMQ Messaging

## What it does
The `rabbitmq-messaging` package provides a robust abstraction for configuring and running a RabbitMQ consumer in Go. It handles dynamic topology setup (exchanges, normal queues, bindings, and optionally a dead-letter-queue) based on configuration structures. Furthermore, it manages automated reconnections, graceful shutdowns via context cancellations, and spawns concurrent workers per channel for reliable message processing.

## Packages used
- `github.com/rabbitmq/amqp091-go`: Core client library for interacting with the RabbitMQ broker via the AMQP 0.9.1 protocol.
- Standard library packages: `context`, `fmt`, `log/slog`, `time`, `sync`.

## Why
Using RabbitMQ directly entails significant boilerplate related to connection handling, channel management, setup of topology, and recovering from disconnections. This package encapsulates that complexity, providing microservices with an easy and declarative way to configure their queues (and optionally DLQ), so developers can focus solely on the business logic inside the message router handlers rather than boilerplate code.
