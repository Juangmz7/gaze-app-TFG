# Block — Infrastructure Layer

## What it does
Implements the persistence, messaging, and API for user blocking.

## Key Design Choices (For New Developers)
- **Composite Keys**: The `BlockEntity` uses a composite key (`BlockEntityId`) representing the blocker and the blocked user, ensuring unique constraints at the database level.
- **Decoupled API**: `BlockController` receives HTTP requests and simply maps them to Commands, pushing business logic to the application layer.

## Components
- `BlockEntity`, `BlockEntityId`
- `JpaBlockRepository`, `BlockRepositoryImpl`
- `BlockController`
- `UserBlockedEventPublisher`
