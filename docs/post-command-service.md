# Post Command Service

## Overview
This service handles the creation, updating, deletion, and interaction (likes, comments, views, shares, collabs) of posts. It uses a **Domain-Driven Design (DDD)** approach with a strict **Hexagonal Architecture** (Domain, Application, Infrastructure layers).

## Domains
The service is divided into distinct bounded contexts (domains):
- **Post**: Core post management (create, update, delete).
- **Collab**: Manages collaborative posts between multiple users.
- **Comment**: Handles adding and managing comments on posts.
- **Like**: Manages user likes on posts.
- **CommentLike**: Manages user likes on comments.
- **Share**: Tracks sharing of posts.
- **View**: Records and aggregates views on posts.
- **Report**: Users report posts and comments (App Store guideline 1.2); ADMIN users list the reports.
- **Shared**: Common infrastructure and cross-cutting concerns.

## Key Design Choices
1. **Clean Architecture**: Dependencies always point inwards. Infrastructure depends on Application; Application depends on Domain. The Domain has zero external dependencies.
2. **Event-Driven**: Communicates asynchronously with other services via RabbitMQ events.
3. **Roles from Keycloak**: `realm_access.roles` is mapped to `ROLE_*` authorities; the moderation lists (`GET /api/posts/reports`, `GET /api/posts/comments/reports`) require the realm role `ADMIN`.

Explore the `docs` folder inside each domain package for detailed layer-specific documentation.
