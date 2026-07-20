# User - Infrastructure Layer

## What it does
The infrastructure layer provides concrete implementations for the application's interfaces. It handles PostgreSQL (JPA), Neo4j, RabbitMQ integration events, mappers, and REST controllers.

## Key Design Choices (For New Developers)
- **Separation of Models**: We separate Domain Models (`User`) from Persistence Models (`UserEntity`, `UserNode`). Mappers (MapStruct) handle the translation. This prevents database concerns from polluting business logic.
- **Idempotency in Listeners**: `UserRabbitMQListener` handles duplicate message detection through `ProcessedEventsRepository`, separately for PostgreSQL and Neo4j targets.
- **Strategy Pattern for Events**: Event publishers implement a common interface. The outbox processor dynamically loops through them, keeping publisher selection outside the application services.
- **Auth-to-User Synchronization**: Auth service register, update, and delete events are consumed from RabbitMQ and converted into `UserService` commands.
- **User Event Projection**: User registered and deleted integration events are consumed back into the service to keep Neo4j user nodes in sync.
- **Authenticated HTTP API**: Controllers derive the requester id from `SecurityUtils`; they do not accept caller ids from request bodies.

## Components
### Persistence
- `UserEntity` (JPA), `UserNode` (Neo4j)
- `JpaUserRepository`, `UserRepositoryImpl`, `UserStatsRepositoryImpl`, `UserNodeRepository`
### Messaging
- `UserRegisteredEventPublisher`, `UserDeletedEventPublisher`, `UserUpdatedEventPublisher`
- `UserRabbitMQListener`
- Incoming auth events: `UserRegisteredFromAuthEvent`, `UserInfoFromAuthUpdatedEvent`, `UserDeletedFromAuthEvent`
- Published user events: `UserRegisteredEvent`, `UserUpdatedEvent`, `UserDeletedEvent`
### Web
- `UserProfileController`, `RecommendedUserController`
- `GET /api/social/profile/me`
- `GET /api/social/profile/{userId}`
- `PUT /api/social/profile`
- `GET /api/social/recommended/users`
