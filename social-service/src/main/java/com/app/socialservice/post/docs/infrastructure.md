# Post - Infrastructure Layer

## What it does
Handles infrastructure integration for the Post domain. This service does not own post persistence, but it consumes post-created and post-deleted events from RabbitMQ so user statistics can stay synchronized.

## Key Design Choices (For New Developers)
- **Event-Driven Integration**: `PostCreatedEvent` and `PostDeletedEvent` deserialize messages from the post events exchange.
- **Stats Projection**: `PostRabbitMQListener` delegates to `UserStatsService` to increment or decrement post counts for the event owner.
- **Idempotency**: Processed post events are recorded so duplicate RabbitMQ deliveries do not double-apply user stats changes.
- **Evolutionary Architecture**: Application and Domain layers can be added later if this service starts owning post behavior.

## Components
- `PostCreatedEvent`, `PostDeletedEvent`
- `PostRabbitMQListener`
