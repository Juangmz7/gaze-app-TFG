# User - Infrastructure Layer

## What it does
The infrastructure layer provides concrete implementations for the application's interfaces. It handles PostgreSQL (JPA), Neo4j, RabbitMQ integration events, mappers, and REST controllers.

## Key Design Choices
- **Separation of Models**: We separate Domain Models (`User`) from Persistence Models (`UserEntity`, `UserNode`). Mappers (MapStruct) handle the translation. This prevents database concerns from polluting business logic.
- **Idempotency in Listeners**: `UserRabbitMQListener` handles duplicate message detection through `ProcessedEventsRepository`, separately for PostgreSQL and Neo4j targets.
- **Strategy Pattern for Events**: Event publishers implement a common interface that declares the exchange and routing key of each event type. The destination is resolved once when the outbox row is inserted, keeping publisher selection outside the application services and the relay generic.
- **Auth-to-User Synchronization**: Auth service register, update, and delete events are consumed from RabbitMQ and converted into `UserService` commands.
- **User Event Projection**: User registered and deleted integration events are consumed back into the service to keep Neo4j user nodes in sync.
- **Authenticated HTTP API**: Controllers derive the requester id from `SecurityUtils`; they do not accept caller ids from request bodies.

## Components
### Persistence
- `UserEntity` (JPA), `UserNode` (Neo4j)
- `JpaUserRepository`, `UserRepositoryImpl`, `UserStatsRepositoryImpl`, `UserNodeRepository`
### Azure Blob
- `AzureProfilePictureStorage`: write-only upload SAS (`sp=cw`, 15 min), bare blob URLs, blob existence (`getProperties`)
- `StorableProfilePictureUrl` request constraint: a container URL is measured without its SAS query
### Messaging
- `UserRegisteredEventPublisher`, `UserDeletedEventPublisher`, `UserUpdatedEventPublisher`
- `UserRabbitMQListener`
- Incoming auth events: `UserRegisteredFromAuthEvent`, `UserInfoFromAuthUpdatedEvent`, `UserDeletedFromAuthEvent`
- Published user events: `UserRegisteredEvent`, `UserUpdatedEvent`, `UserDeletedEvent`
### Web
- `UserProfileController`, `RecommendedUserController`
- `GET /api/social/profile/me`
- `GET /api/social/profile/{userId}`
- `GET /api/social/profile/by-username/{username}` (same body; `JpaUserRepository.findIdByLowercaseUsername`)
- `PUT /api/social/profile` and `PUT /api/social/profile/me`
- `POST /api/social/profile/me/picture/upload-url`
- Every returned picture is read-signed by `PictureUrlResponseSigner` in the controller (never inside cached services)
- `GET /api/social/recommended/users`
