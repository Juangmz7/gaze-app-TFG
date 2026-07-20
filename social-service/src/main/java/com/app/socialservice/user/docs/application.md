# User - Application Layer

## What it does
The application layer orchestrates user registration, auth-info synchronization, profile reads/updates, user stats, and recommendations. It receives commands from HTTP controllers and RabbitMQ listeners, applies domain logic through the `User` aggregate, and delegates persistence to repository interfaces.

## Key Design Choices (For New Developers)
- **Command Pattern**: Input data is encapsulated into Commands (e.g., `UserRegisterCommand`, `UpdateOwnUserProfileCommand`). This decouples the use case from the delivery mechanism (HTTP vs RabbitMQ).
- **Dependency Inversion**: The application layer defines interfaces for repositories (e.g., `UserRepository`) but does not implement them. This allows testing business logic without a database.
- **Dual Database Synchronization**: `UserService` writes to PostgreSQL and creates outbox events, while `UserNodeService` applies user-created and user-deleted integration events to Neo4j.
- **Transactional Boundaries**: Write methods are annotated with `@Transactional`. Domain changes and outbox event creation happen atomically.
- **Profile Caching**: `UserProfileService` caches own-profile and public-profile responses through Spring cache names defined in `CacheNames`. Updating the own profile refreshes the own-profile cache and evicts public-profile entries.
- **Recommendation Flow**: `RecommendedUserService` asks Neo4j for graph candidates, excludes users blocked by the requester, loads relational profile details, deduplicates candidates, and returns up to 20 ranked results.

## Components
### Commands & Services
- `UserService`, `UserNodeService`, `RecommendedUserService`, `UserProfileService`, `UserStatsService`
- `UserRegisterCommand`, `UpdateAuthUserInfoCommand`, `DeleteUserCommand`
- `UpdateOwnUserProfileCommand`, `SynchroniseSecondaryDatabaseCommand`
### Interfaces
- `UserRepository`, `UserStatsRepository`
