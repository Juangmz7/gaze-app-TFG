# Shared - Infrastructure Layer

## What it does
Contains cross-cutting infrastructure implementations: security, API error handling, datasource configuration, Redis caching, RabbitMQ configuration, listener support, observability, and the transactional outbox engine.

## Key Design Choices (For New Developers)
- **Centralized Outbox Processing**: The outbox pattern implementation (`ImmediateOutboxSender`, `OutboxRetryWorker`) lives here. It dispatches `OutboxEvent` rows through `EventPublisher` implementations and retries pending messages.
- **Global Error Handling**: `ApiExceptionHandler` intercepts exceptions and formats standard API error responses, keeping controllers clean.
- **Security Configuration**: Centralized Spring Security setup for JWT validation.
- **RabbitMQ Schema Ownership**: `RabbitMQConfig` declares exchanges, queues, dead-letter queues, bindings, JSON conversion, retry advice, and listener container settings from `RabbitMQProperties`.
- **Idempotent Message Handling**: `AbstractRabbitMQListenerSupport` provides duplicate detection and processed-event recording through `ProcessedEventsRepository`.
- **Multi-Store Configuration**: JPA/PostgreSQL, and Redis configuration are centralized here so feature modules depend on repository interfaces and Spring beans rather than connection details.

## Components
- `DatasourceConfig`, `JpaConfig`, `RedisConfig`
- `SecurityConfig`, `SecurityUtils`, `CustomAuthenticationEntryPoint`, `ApiExceptionHandler`
- `RabbitMQConfig`, `RabbitMQProperties`, `EventPublisher`
- `AbstractRabbitMQListenerSupport`
- `OutboxEvent`, `ProcessedEvent`, `OutboxEventRepository`, `ProcessedEventsRepository`
- `ImmediateOutboxSender`, `OutboxRetryWorker`
- `EventMessage`, `JsonMapper`
