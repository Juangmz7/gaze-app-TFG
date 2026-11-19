# Follow - Infrastructure Layer

## What it does
Provides JPA and Neo4j repository implementations, RabbitMQ publishers/listeners, event mappers, and REST endpoints for the follow feature.

## Key Design Choices
- **Neo4j Cypher Optimization**: Custom graph repositories (`FollowGraphNeo4jRepository`) allow us to run complex social queries (like mutual followers or recommendations) efficiently using Cypher.
- **Event Mappers**: Converts domain `Follow` state into integration events for RabbitMQ (`UserFollowedEvent`, `UserUnfollowedEvent`).
- **Event-Driven Graph Updates**: `FollowRabbitMQListener` listens for published follow/unfollow events and updates Neo4j through `FollowNodeService`.
- **Authenticated HTTP API**: `FollowController` uses the authenticated user id as the follower id.
- **Keyset list queries**: `JpaFollowRepository` has one native query for the first page and one for the next pages of each list (an "OR no cursor" branch would stop the index from serving the row comparison). They join ACCEPTED users, leave out block relations with the viewer (two primary key lookups on `blocks`) and are served by `idx_follows_followers_page` / `idx_follows_following_page` (declared on `FollowEntity` and in `db/schema/social-service-prod.sql`). `cursor` and `limit` are bound as text so a malformed value answers `invalid_cursor` / `invalid_limit`.

## Components
- `FollowEntity`, `FollowEntityId`
- `JpaFollowRepository`, `FollowRepositoryImpl`, `FollowGraphNeo4jRepository`
- `FollowController`
- `UserFollowedEventPublisher`, `UserUnfollowedEventPublisher`
- `FollowRabbitMQListener`
- `POST /api/social/follow`
- `DELETE /api/social/follow`
- `GET /api/social/follow/{userId}/followers`, `GET /api/social/follow/{userId}/following` (`?cursor=&limit=`)
