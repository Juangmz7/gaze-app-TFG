# Shared — Infrastructure Layer

## What it does
Contains cross-cutting infrastructure implementations: Security, API error handling, Redis configuration, and the **Transactional Outbox Engine**.

## Key Design Choices (For New Developers)
- **Centralized Outbox Processing**: The outbox pattern implementation (`ImmediateOutboxSender`, `OutboxRetryWorker`) lives here. It polls the `OutboxEvent` table and dispatches to RabbitMQ, ensuring reliable messaging system-wide.
- **Global Error Handling**: `ApiExceptionHandler` intercepts exceptions and formats standard API error responses, keeping controllers clean.
- **Security Configuration**: Centralized Spring Security setup for JWT validation.
