# Follow - Infrastructure Layer

## What it does
Provides JPA and Neo4j repository implementations, RabbitMQ publishers/listeners, event mappers, and REST endpoints for the follow feature.

## Key Design Choices (For New Developers)
- **Neo4j Cypher Optimization**: Custom graph repositories (`FollowGraphNeo4jRepository`) allow us to run complex social queries (like mutual followers or recommendations) efficiently using Cypher.
- **Event Mappers**: Converts domain `Follow` state into integration events for RabbitMQ (`UserFollowedEvent`, `UserUnfollowedEvent`).
- **Event-Driven Graph Updates**: `FollowRabbitMQListener` listens for published follow/unfollow events and updates Neo4j through `FollowNodeService`.
- **Authenticated HTTP API**: `FollowController` uses the authenticated user id as the follower id.

## Components
- `FollowEntity`, `FollowEntityId`
- `JpaFollowRepository`, `FollowRepositoryImpl`, `FollowGraphNeo4jRepository`
- `FollowController`
- `UserFollowedEventPublisher`, `UserUnfollowedEventPublisher`
- `FollowRabbitMQListener`
- `POST /api/social/follow`
- `DELETE /api/social/follow`
