# Social Service

## What it does
The `social-service` handles social interactions and user profile management. Currently, it implements the `User` domain, allowing user registration, authentication information updates, and user deletion. It listens to domain events from other systems (like authentication) to keep its local state in sync.

## Packages used
- `spring-boot-starter-data-jpa`: For database persistence of the `UserEntity`.
- `mapstruct`: Used for mapping domain events to infrastructure events (`UserEventMapper`).
- Shared common packages (`com.app.socialservice.shared`) for Outbox pattern events and JSON processing.

## Why
- **Outbox Pattern**: The service uses an outbox pattern (`OutboxEvent`, `ProcessedEvent`) to ensure reliable message delivery to the message broker after a local transaction succeeds. This guarantees at-least-once delivery of domain events without using distributed transactions.
- **Idempotency**: It tracks processed correlation IDs in `ProcessedEventsRepository` to safely discard duplicate events and handle retries effectively.
- **Event-Driven**: By relying on `UpdateAuthUserInfoCommand` and `DeleteUserCommand`, the service keeps its local state decoupled from the source of truth for auth data, achieving high cohesion and loose coupling.
