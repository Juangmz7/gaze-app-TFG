# Block - Infrastructure Layer

## What it does
Implements persistence, messaging, event mapping, graph projection, and the HTTP API for user blocking.

## Key Design Choices (For New Developers)
- **Composite Keys**: The `BlockEntity` uses a composite key (`BlockEntityId`) representing the blocker and the blocked user, ensuring unique constraints at the database level.
- **Decoupled API**: `BlockController` receives HTTP requests and simply maps them to Commands, pushing business logic to the application layer.
- **Event Publishing**: `UserBlockedEventPublisher` and `UserUnblockedEventPublisher` publish outbox messages to the user events exchange.
- **Block-Created Listener**: `BlockRabbitMQListener` consumes block-created events and deletes bidirectional follow relationships from Neo4j. There is no block-deleted queue/listener declared in this service.
- **Authenticated HTTP API**: `BlockController` uses the authenticated user id as the blocker/unblocker id.

## Components
- `BlockEntity`, `BlockEntityId`
- `JpaBlockRepository`, `BlockRepositoryImpl`
- `BlockController`
- `UserBlockedEventPublisher`, `UserUnblockedEventPublisher`
- `BlockRabbitMQListener`
- `BlockEventMapper`
- `POST /api/social/block`
- `DELETE /api/social/block`
