# Shared — Infrastructure Layer

## What it does

The shared infrastructure layer provides cross-cutting infrastructure components used across all modules: the **transactional outbox pattern**, **RabbitMQ configuration**, **data source setup**, **JSON serialization**, **observability**, and shared JPA entities/repositories.

## Package Structure

```
shared/infrastructure/
├── datasource/
│   └── DatasourceConfig.java
├── entity/
│   ├── OutboxEvent.java
│   └── ProcessedEvents.java
├── enums/
│   └── EventStatus.java
├── events/
│   └── EventMessage.java
├── exceptions/
│   ├── EventPublisherNotFound.java
│   └── OutboxEventNotFoundException.java
├── mapper/
│   └── JsonMapper.java
├── observability/
│   └── InstallOpenTelemetryAppender.java
├── outbox/
│   ├── ImmediateOutboxSender.java
│   └── OutboxRetryWorker.java
├── rabbitmq/
│   ├── config/
│   │   ├── RabbitMQConfig.java
│   │   └── RabbitMQProperties.java
│   ├── listener/
│   │   ├── AbstractRabbitMQListenerSupport.java
│   │   ├── BlockRabbitMQListener.java
│   │   ├── FollowRabbitMQListener.java
│   │   └── UserRabbitMQListener.java
│   └── publisher/
│       └── EventPublisher.java
└── repository/
    ├── OutboxEventRepository.java
    └── ProcessedEventsRepository.java
```

## Components

### Outbox Pattern

The core reliability mechanism for event publishing. Ensures **at-least-once delivery** by writing events to the database in the same transaction as the business data.

#### `OutboxEvent` (JPA Entity)

Persisted event record in PostgreSQL:

| Field | Type | Nullable | Annotations | Description |
|-------|------|----------|-------------|-------------|
| `id` | `UUID` | No | `@Id`, `@Column(nullable=false)` | Unique event ID |
| `correlationId` | `UUID` | No | `@Column(nullable=false)` | Trace correlation |
| `payload` | `String` | No | `@Column(columnDefinition="TEXT", nullable=false)` | Serialized event JSON |
| `eventType` | `String` | No | `@Column(nullable=false)` | Event class name for publisher routing |
| `status` | `EventStatus` | No | `@Enumerated(STRING)`, `@Column(nullable=false)` | PENDING or PROCESSED |
| `createdAt` | `Instant` | No | `@Column(nullable=false)` | Event timestamp |

#### `ProcessedEvents` (JPA Entity)

Idempotency guard — tracks which correlation IDs have already been processed:

| Field | Type | Nullable | Annotations | Description |
|-------|------|----------|-------------|-------------|
| `id` | `UUID` | No | `@Id` | The correlation ID itself |
| `processedAt` | `Instant` | No | `@Column(nullable=false)`, `@PrePersist` | Auto-set on persist |

#### `ImmediateOutboxSender`

Listens for `DomainEvent` via `@TransactionalEventListener(phase = AFTER_COMMIT)`. After the business transaction commits:

1. Looks up the `OutboxEvent` by the domain event's `id`
2. Finds the matching `EventPublisher` via the strategy pattern
3. Publishes to RabbitMQ
4. Marks the event as `PROCESSED`
5. On failure, logs a warning — the `OutboxRetryWorker` will pick it up

#### `OutboxRetryWorker`

A `@Scheduled` background worker that retries failed/pending events:

- Runs at a configurable interval (`${outbox.retry.delay-ms:300000}` — default 5 minutes)
- Fetches a batch of up to 100 `PENDING` events ordered by `createdAt` (oldest first)
- Iterates through publishers and retries each event
- On success: marks as `PROCESSED`
- On failure: logs and leaves for the next retry cycle

#### `EventStatus` (Enum)

- `PENDING` — Event created but not yet published
- `PROCESSED` — Event successfully published to RabbitMQ

### Event Contracts

#### `EventMessage` (Interface)

Standard contract for all outgoing infrastructure events:

```java
public interface EventMessage {
    UUID id();
    UUID correlationId();
    Instant occurredAt();
}
```

#### `EventPublisher` (Strategy Interface)

```java
public interface EventPublisher {
    boolean supports(String eventType);
    void publish(OutboxEvent outboxEvent);
}
```

Each module provides its own `EventPublisher` implementation. The outbox components iterate over all registered publishers and dispatch based on `supports()`.

### RabbitMQ

#### `RabbitMQConfig`

Declarative RabbitMQ topology:

- **Queues**: `auth.register`, `auth.update`, `auth.delete`, `user.register`, `user.deleted`, `follow.created`, `follow.deleted`, `user.block.created`, `post.created`, `post.deleted`
- **Dead Letter Queues (DLQ)**: Each queue has a `.dlq` counterpart, including `q.social-service.follow.deleted.dlq` and `q.social-service.post.created.dlq`
- **Exchanges**: `x.auth.events` (topic), `x.auth.events.dlx` (direct), `x.user.events` (topic), `x.user.events.dlx` (direct), `x.post.events` (topic), `x.post.events.dlx` (direct)
- **Bindings**: Routes messages by routing key

The follow queues are declared from `RabbitMQProperties.queue.user.follow.created` and
`RabbitMQProperties.queue.user.follow.deleted`, the block-created queue from
`RabbitMQProperties.queue.user.block.created`, and the post queues from
`RabbitMQProperties.queue.post.created` and `RabbitMQProperties.queue.post.deleted`.
Bindings use the matching typed routing keys from `RabbitMQProperties.rk.*`, so the topology stays aligned with
the existing configuration-properties pattern instead of hardcoded Java constants.

Also configures:
- `SimpleRabbitListenerContainerFactory` with stateless retry (max 3 retries, exponential backoff)
- `JacksonJsonMessageConverter` for JSON serialization
- `RabbitTemplate` with the JSON converter

#### `RabbitMQProperties`

Type-safe configuration properties bound to `rabbitmq.*` in `application.yaml`. Nested structure mirrors the queue/exchange/routing-key hierarchy.

#### RabbitMQ listeners

Listener responsibilities are now split by domain while preserving the same queue and routing-key usage:

1. **`UserRabbitMQListener`** — Owns `${rabbitmq.queue.auth.register}`, `${rabbitmq.queue.auth.update}`, `${rabbitmq.queue.auth.delete}`, `${rabbitmq.queue.user.register}`, and `${rabbitmq.queue.user.deleted}`. It validates auth and user payloads, derives deterministic IDs for auth-originated events, performs duplicate detection with `ProcessedEventsRepository`, delegates to `UserService` or `UserNodeService`, and records processed messages on success.
2. **`FollowRabbitMQListener`** — Owns `${rabbitmq.queue.user.follow.created}`. It validates `UserFollowedEvent`, performs idempotency bookkeeping, and delegates Neo4j `FOLLOWS` synchronization to `FollowNodeService`.
3. **`BlockRabbitMQListener`** — Owns `${rabbitmq.queue.user.block.created}`. It validates `UserBlockedEvent`, performs idempotency bookkeeping, and delegates Neo4j cleanup to `BlockNodeService`.
4. **`AbstractRabbitMQListenerSupport`** — Shared helper base containing defensive broker-payload validation, deterministic UUID generation, and processed-event persistence helpers used by the concrete listeners.

Auth-sync handlers swallow failures after logging because they create or update local PostgreSQL state from upstream events.
The follow and block graph-sync handlers rethrow after logging so the Rabbit listener retry/DLQ policy can handle Neo4j synchronization failures.

### Datasource

#### `DatasourceConfig`

Wraps the `HikariDataSource` in a `LazyConnectionDataSourceProxy` to defer physical connection acquisition until the first SQL statement. This improves performance for transactions that may not need a database connection (e.g. cache hits, early returns).

### Observability

#### `InstallOpenTelemetryAppender`

Installs the OpenTelemetry Logback appender on application startup via `InitializingBean`. This bridges Logback logs into the OpenTelemetry trace/span context for distributed tracing.

### Mapper

#### `JsonMapper`

A simple wrapper around Jackson's `ObjectMapper` providing `toJson()` and `fromJson()` methods with proper exception handling. Used to serialize outbox event payloads.

### Repositories

- **`OutboxEventRepository`** — `JpaRepository<OutboxEvent, UUID>` with a custom method `findOutboxEventByStatus(EventStatus, Pageable)` for batch retry queries.
- **`ProcessedEventsRepository`** — `JpaRepository<ProcessedEvents, UUID>` for idempotency tracking.

### Exceptions

- **`EventPublisherNotFound`** — No publisher registered for a given event type
- **`OutboxEventNotFoundException`** — OutboxEvent not found by ID during publishing

## Packages Used

| Package | Purpose |
|---------|---------|
| `org.springframework.amqp.*` | RabbitMQ AMQP support |
| `org.springframework.data.jpa.repository.JpaRepository` | PostgreSQL persistence |
| `org.springframework.transaction.event.TransactionalEventListener` | Post-commit event handling |
| `org.springframework.scheduling.annotation.Scheduled` | Background retry |
| `org.springframework.boot.context.properties.ConfigurationProperties` | Type-safe config |
| `com.fasterxml.jackson.databind.ObjectMapper` | JSON serialization |
| `com.zaxxer.hikari.HikariDataSource` | Connection pooling |
| `io.opentelemetry.*` | Distributed tracing |
| `jakarta.persistence.*` | JPA annotations |
| `lombok.*` | Boilerplate reduction |

## Why

- **Transactional outbox pattern**: Guarantees that events are persisted atomically with the business data. No event is lost even if RabbitMQ is temporarily unavailable. The retry worker provides a safety net.
- **Strategy pattern for publishers**: New event types only require a new `EventPublisher` implementation — no changes to the outbox infrastructure.
- **DLQ configuration**: Failed messages after retries are routed to dead-letter queues for manual inspection, preventing message loss.
- **LazyConnectionDataSourceProxy**: Avoids holding database connections for read-heavy or short-circuit operations.
- **Domain-split listeners**: User, follow, and block handlers are separated so each listener stays aligned with one bounded-context workflow while still sharing the same validation and idempotency support code.
- **`@Enumerated(STRING)`**: Stores enum values as readable strings in the database rather than ordinal integers, making data inspection and debugging easier.
