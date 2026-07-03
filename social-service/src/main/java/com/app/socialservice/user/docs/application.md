# User — Application Layer

## What it does
The application layer orchestrates use cases. It receives commands, applies domain logic via the aggregate root, and delegates persistence to interfaces.

## Key Design Choices (For New Developers)
- **Command Pattern**: Input data is encapsulated into Commands (e.g., `UserRegisterCommand`). This decouples the use case from the delivery mechanism (HTTP vs RabbitMQ).
- **Dependency Inversion**: The application layer defines interfaces for repositories (e.g., `UserRepository`) but does not implement them. This allows testing business logic without a database.
- **Dual Database Synchronization**: `UserService` writes to PostgreSQL, while `UserNodeService` writes to Neo4j. This separation of concerns enables independent scaling and graph-optimized queries.
- **Transactional Boundaries**: Methods are annotated with `@Transactional`. Domain changes and Outbox event creation happen atomically.

## Components
### Commands & Services
- `UserService`, `UserNodeService`, `RecommendedUserService`, `UserProfileService`, `UserStatsService`
- `UserRegisterCommand`, `UpdateAuthUserInfoCommand`, `DeleteUserCommand`
### Interfaces
- `UserRepository`, `UserStatsRepository`
