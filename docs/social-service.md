# Social Service

## Overview
This service handles social interactions and user profile management. It uses a **Domain-Driven Design (DDD)** approach with a strict **Hexagonal Architecture** (Domain, Application, Infrastructure layers).

## Domains
The service is divided into distinct bounded contexts (domains):
- **User**: Core user profile and identity management.
- **Follow**: Manages the directed follow graph between users.
- **Block**: Handles user blocking mechanisms to prevent unwanted interactions.
- **Post**: Listens to post-related events from other services.
- **Shared**: Common infrastructure, cross-cutting concerns, and outbox pattern implementation.

## Key Design Choices
1. **Clean Architecture**: Dependencies always point inwards. Infrastructure depends on Application; Application depends on Domain. The Domain has zero external dependencies (no Spring or DB annotations).
2. **Polyglot Persistence**: We use PostgreSQL as our primary source of truth (ACID guarantees), and Neo4j as a secondary graph database (for traversing social connections like friends-of-friends).
3. **Transactional Outbox Pattern**: To reliably publish events to RabbitMQ without distributed transactions, we save events to an `outbox` table in the *same transaction* as the domain changes. A relay then publishes them to RabbitMQ with publisher confirms (at-least-once; consumers deduplicate by message id). PostgreSQL is the source of truth and Neo4j is a projection fed by those events, so the outbox never spans two databases.
4. **Event-Driven**: We avoid synchronous HTTP calls between microservices where possible. State changes are communicated via RabbitMQ events.

Explore the `docs` folder inside each domain package for detailed layer-specific documentation.
