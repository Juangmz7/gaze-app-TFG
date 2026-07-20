# Social Service

Spring Boot service for social user data, profiles, follow relationships, block relationships, recommendations, and social counters.

## Runtime Stack
- Java 25
- Spring Boot 4.0.6
- PostgreSQL through Spring Data JPA
- Neo4j through Spring Data Neo4j
- Redis through Spring Cache
- RabbitMQ through Spring AMQP
- OAuth2 resource server/JWT security
- Transactional outbox with RabbitMQ publishers

## Main Capabilities
- Maintains user records from auth-service register, update, and delete events.
- Stores user profile data and caches own/public profile reads.
- Stores follow relationships in PostgreSQL and projects active graph edges to Neo4j.
- Stores block relationships and removes affected follow relationships.
- Generates user recommendations from Neo4j candidates, excluding blocked users and ranking by common connections.
- Maintains follower, following, and post counters in Redis from follow and post events.

## HTTP API
All endpoints use the authenticated JWT user id from `SecurityUtils`.

- `GET /api/social/profile/me`
- `GET /api/social/profile/{userId}`
- `PUT /api/social/profile`
- `GET /api/social/recommended/users`
- `POST /api/social/follow`
- `DELETE /api/social/follow`
- `POST /api/social/block`
- `DELETE /api/social/block`

## Messaging
Queue and routing-key names are configured in `src/main/resources/application.yaml`.

Consumed queues:
- `q.social-service.auth.register`
- `q.social-service.auth.update`
- `q.social-service.auth.delete`
- `q.social-service.user.register`
- `q.social-service.user.deleted`
- `q.social-service.user.follow.created`
- `q.social-service.user.follow.deleted`
- `q.social-service.user.block.created`
- `q.social-service.post.created`
- `q.social-service.post.deleted`

Published integration events:
- `UserRegisteredEvent`
- `UserUpdatedEvent`
- `UserDeletedEvent`
- `UserFollowedEvent`
- `UserUnfollowedEvent`
- `UserBlockedEvent`
- `UserUnblockedEvent`

`UserUnblockedEvent` is published to routing key `rk.user.block.deleted`. This service currently does not declare or consume a block-deleted queue.

## Configuration
Profiles:
- `dev`: local development values in `application-dev.yaml`
- `prod`: environment-variable based values in `application-prod.yaml`
- `test`: integration-test configuration in `src/test/resources/application-test.yaml`

Production environment variables include PostgreSQL, Redis, Neo4j, RabbitMQ, and Keycloak issuer settings. See `application-prod.yaml` for the exact names.

## Tests
Run all tests with:

```bash
./test.sh
```

or directly:

```bash
./mvnw test -Dspring.profiles.active=test --no-transfer-progress
```

Integration tests use Testcontainers for PostgreSQL, Neo4j, RabbitMQ, Redis-related configuration, and observability dependencies where needed.

## Package Docs
Feature-specific architecture notes live beside the code:

- `src/main/java/com/app/socialservice/user/docs`
- `src/main/java/com/app/socialservice/follow/docs`
- `src/main/java/com/app/socialservice/block/docs`
- `src/main/java/com/app/socialservice/post/docs`
- `src/main/java/com/app/socialservice/shared/docs`
