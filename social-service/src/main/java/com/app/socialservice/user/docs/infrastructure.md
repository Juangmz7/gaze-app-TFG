# User — Infrastructure Layer

## What it does
The infrastructure layer provides concrete implementations for the application's interfaces. It handles PostgreSQL (JPA), Neo4j, RabbitMQ, and REST controllers.

## Key Design Choices (For New Developers)
- **Separation of Models**: We separate Domain Models (`User`) from Persistence Models (`UserEntity`, `UserNode`). Mappers (MapStruct) handle the translation. This prevents database concerns from polluting business logic.
- **Idempotency in Listeners**: RabbitMQ listeners handle duplicate message detection. The application services don't need to know about at-least-once delivery caveats.
- **Strategy Pattern for Events**: Event publishers implement a common interface. The Outbox processor dynamically loops through them, ensuring the Open/Closed principle when adding new events.

## Components
### Persistence
- `UserEntity` (JPA), `UserNode` (Neo4j)
- `JpaUserRepository`, `UserNodeRepository`
### Messaging
- `UserRegisteredEventPublisher`, `UserDeletedEventPublisher`, `UserUpdatedEventPublisher`
### Web
- `UserProfileController`, `RecommendedUserController`
