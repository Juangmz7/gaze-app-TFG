# User - Application Layer

## What it does
The application layer orchestrates user registration, auth-info synchronization, profile reads/updates, user stats, and recommendations. It receives commands from HTTP controllers and RabbitMQ listeners, applies domain logic through the `User` aggregate, and delegates persistence to repository interfaces.

## Key Design Choices
- **Command Pattern**: Input data is encapsulated into Commands (e.g., `UserRegisterCommand`, `UpdateOwnUserProfileCommand`). This decouples the use case from the delivery mechanism (HTTP vs RabbitMQ).
- **Dependency Inversion**: The application layer defines interfaces for repositories (e.g., `UserRepository`) but does not implement them. This allows testing business logic without a database.
- **Dual Database Synchronization**: `UserService` writes to PostgreSQL and creates outbox events, while `UserNodeService` applies user-created and user-deleted integration events to Neo4j.
- **Transactional Boundaries**: Write methods are annotated with `@Transactional`. Domain changes and outbox event creation happen atomically.
- **User Stats in PostgreSQL**: followers and following are counted from the active rows of `follows`, which follow, unfollow and block change in their own transaction, so they cannot drift. The post count is the one counter (`user_stats`): its events come from post-command-service, and `PostRabbitMQListener` changes it in the same transaction that records the event as processed, only when that insert happened, so a redelivery counts nothing.
- **Caching** (Redis, `CacheNames`), every cache keyed by one user id:
  - `user-own-profile` (30 min): `UserProfileService.getOwnProfile`; `updateOwnUserProfile` replaces it.
  - `user-public-profile` (30 min): `PublicUserProfileReader`, the part of a profile that is the same whoever asks. `getUserProfile` reads following, follows me and blocked on every request.
  - `user-recommended-users` (5 min): `RecommendedUserService`, per requester.
  - `UserCacheEvictor` evicts exactly the users a change touched: follows, blocks, post counts, profile and auth-info updates, deletion. The cache manager is transaction aware, so the eviction runs after the commit.
  - A banned or missing user throws and is never cached. Nothing in this service bans a user: a ban applied elsewhere must evict that user's profiles.
- **Recommendation Flow**: `RecommendedUserService` asks Neo4j for graph candidates, excludes users blocked by the requester, loads relational profile details, deduplicates candidates, and returns up to 20 ranked results.

## Components
### Commands & Services
- `UserService`, `UserNodeService`, `RecommendedUserService`, `UserProfileService`, `UserStatsService`
- `UserRegisterCommand`, `UpdateAuthUserInfoCommand`, `DeleteUserCommand`
- `UpdateOwnUserProfileCommand`, `SynchroniseSecondaryDatabaseCommand`
### Interfaces
- `UserRepository`, `UserStatsRepository`
