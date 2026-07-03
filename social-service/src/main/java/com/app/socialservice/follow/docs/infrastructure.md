# Follow — Infrastructure Layer

## What it does
Provides the JPA/Neo4j repository implementations, RabbitMQ publishers, and REST endpoints for the follow feature.

## Key Design Choices (For New Developers)
- **Neo4j Cypher Optimization**: Custom graph repositories (`FollowGraphNeo4jRepository`) allow us to run complex social queries (like mutual followers or recommendations) efficiently using Cypher.
- **Event Mappers**: Converts domain `Follow` state into integration events for RabbitMQ (`UserFollowedEvent`).

## Components
- `FollowEntity`, `FollowEntityId`
- `JpaFollowRepository`, `FollowRepositoryImpl`, `FollowGraphNeo4jRepository`
- `FollowController`
- `UserFollowedEventPublisher`, `UserUnfollowedEventPublisher`
