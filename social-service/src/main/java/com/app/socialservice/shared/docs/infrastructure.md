# Shared - Infrastructure Layer

## What it does
Contains cross-cutting infrastructure implementations: security, API error handling, datasource configuration, Redis caching, RabbitMQ configuration, listener support, observability, and the transactional outbox engine.

## Key Design Choices
- **Centralized Outbox Processing**: The outbox relay lives here. `OutboxDestinationResolver` stores each row's exchange and routing key at insert time (from the `EventPublisher` that supports its event type), inside the business transaction. `OutboxRelay` claims rows one at a time (`FOR UPDATE SKIP LOCKED`, safe with several instances), publishes them as persistent JSON with publisher confirms and marks them `PROCESSED` only after the broker ack. Failures go back to `PENDING` with `attempts`/`last_error` and stop the batch to keep ordering; after 10 attempts the row is `FAILED`. Unroutable messages (acked but returned) are logged and marked processed. `ImmediateOutboxSender` publishes each row right after commit through the same path; a daily job deletes processed rows older than 7 days. Delivery is at-least-once, so consumers deduplicate by message id.
- **Global Error Handling**: `ApiExceptionHandler` intercepts exceptions and formats standard API error responses, keeping controllers clean.
- **Security Configuration**: Centralized Spring Security setup for JWT validation.
- **RabbitMQ Schema Ownership**: `RabbitMQConfig` declares exchanges, queues, dead-letter queues, bindings, JSON conversion, retry advice, and listener container settings from `RabbitMQProperties`.
- **Idempotent Message Handling**: `AbstractRabbitMQListenerSupport` provides duplicate detection and processed-event recording through `ProcessedEventsRepository`.
- **Multi-Store Configuration**: JPA/PostgreSQL, Neo4j, and Redis configuration are centralized here so feature modules depend on repository interfaces and Spring beans rather than connection details.

## Components
- `DatasourceConfig`, `JpaConfig`, `Neo4jConfig`, `RedisConfig`
- `SecurityConfig`, `SecurityUtils`, `CustomAuthenticationEntryPoint`, `ApiExceptionHandler`
- `RabbitMQConfig`, `RabbitMQProperties`, `EventPublisher`
- `AbstractRabbitMQListenerSupport`, `UserRabbitMQListener`, `FollowRabbitMQListener`, `BlockRabbitMQListener`, `PostRabbitMQListener`
- `OutboxEvent`, `ProcessedEvent`, `OutboxEventRepository`, `ProcessedEventsRepository`
- `ImmediateOutboxSender`, `OutboxRelay`, `OutboxDestinationResolver`, `OutboxDestination`
- `EventMessage`, `JsonMapper`
