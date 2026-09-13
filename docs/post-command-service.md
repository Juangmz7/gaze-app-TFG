# Post Command Service

## Overview
Welcome to the `post-command-service`! This service handles the creation, updating, deletion, and interaction (likes, comments, views, shares, collabs) of posts. It uses a **Domain-Driven Design (DDD)** approach with a strict **Hexagonal Architecture** (Domain, Application, Infrastructure layers).

## Domains
The service is divided into distinct bounded contexts (domains):
- **Post**: Core post management (create, update, delete).
- **Collab**: Manages collaborative posts between multiple users.
- **Comment**: Handles adding and managing comments on posts.
- **Like**: Manages user likes on posts.
- **CommentLike**: Manages user likes on comments.
- **Share**: Tracks sharing of posts.
- **View**: Records and aggregates views on posts.
- **Shared**: Common infrastructure and cross-cutting concerns.

## Key Design Choices (For New Developers)
1. **Clean Architecture**: Dependencies always point inwards. Infrastructure depends on Application; Application depends on Domain. The Domain has zero external dependencies.
2. **Event-Driven**: Communicates asynchronously with other services via RabbitMQ events.

Explore the `docs` folder inside each domain package for detailed layer-specific documentation.
