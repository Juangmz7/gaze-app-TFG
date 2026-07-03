# Social Service

## Overview
Welcome to the `social-service`! If you are new to the codebase, this is the best place to start.
This service handles social interactions and user profile management. It uses a **Domain-Driven Design (DDD)** approach with a strict **Hexagonal Architecture** (Domain, Application, Infrastructure layers).

## Domains
The service is divided into distinct bounded contexts (domains):
- **User**: Core user profile and identity management.
- **Follow**: Manages the directed follow graph between users.
- **Block**: Handles user blocking mechanisms to prevent unwanted interactions.
- **Post**: Listens to post-related events from other services.
- **Shared**: Common infrastructure, cross-cutting concerns, and outbox pattern implementation.

## Key Design Choices (For New Developers)
1. **Clean Architecture**: Dependencies always point inwards. Infrastructure depends on Application; Application depends on Domain. The Domain has zero external dependencies (no Spring or DB annotations).
2. **Polyglot Persistence**: We use PostgreSQL as our primary source of truth (ACID guarantees), and Neo4j as a secondary graph database (for traversing social connections like friends-of-friends).
3. **Transactional Outbox Pattern**: To reliably publish events to RabbitMQ without distributed transactions, we save events to an `outbox` table in the *same transaction* as the domain changes. A separate process then relays them to RabbitMQ.
4. **Event-Driven**: We avoid synchronous HTTP calls between microservices where possible. State changes are communicated via RabbitMQ events.

Explore the `docs` folder inside each domain package for detailed layer-specific documentation.
