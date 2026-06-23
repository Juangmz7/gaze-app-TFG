# User — Application Layer

## What it does

The application layer orchestrates use cases for the `user` module. It receives **commands** from the infrastructure layer (e.g. RabbitMQ listeners), coordinates domain logic, persists data, and publishes events via the transactional outbox pattern.

## Package Structure

```
user/application/
├── commands/
│   ├── SynchroniseSecondaryDatabaseCommand.java
│   └── UserRegisterCommand.java
├── dto/
│   └── UserRegisteredPayload.java
├── mapper/
│   └── UserMapper.java
├── repository/
│   └── UserRepository.java
└── service/
    ├── UserNodeService.java
    └── UserService.java
```

## Components

### Commands

Simple `record` types carrying the data needed to execute a use case. They are framework-agnostic and created by mappers in the infrastructure layer.

#### `UserRegisterCommand`

| Field | Type | Description |
|-------|------|-------------|
| `id` | `UUID` | Command/event identifier |
| `correlationId` | `UUID` | Correlation ID for tracing |
| `userId` | `UUID` | User's unique ID (from auth service) |
| `username` | `String` | Username |
| `email` | `String` | Email address |
| `occurredOn` | `Instant` | Timestamp of the original event |

#### `SynchroniseSecondaryDatabaseCommand`

| Field | Type | Description |
|-------|------|-------------|
| `correlationId` | `UUID` | Correlation ID for tracing |
| `eventId` | `UUID` | Source event identifier |
| `userId` | `UUID` | User's unique ID to sync |

### DTOs

#### `UserRegisteredPayload`

A data transfer record used when serializing user registration event data.

### Repositories (Interfaces)

#### `UserRepository`

The application-layer interface for user persistence. Declares methods like `save()`, `findById()`, and `existsById()` using domain types (and standard Java types) decoupled from any framework like Spring Data JPA.

### Mappers

#### `UserMapper`

MapStruct interface responsible for bidirectional mapping between the domain `User` model and the infrastructure `UserEntity`. Also contains default methods to wrap/unwrap value objects.

### Services

#### `UserService`

The primary use-case handler for user registration. Orchestrates:

1. **Duplicate user check** — Verifies the user doesn't already exist
2. **Domain object creation** — Creates a `User` with value objects (`UserId`, `Username`, `Email`)
3. **Persistence** — Saves the user via `UserRepository`
4. **Outbox event creation** — Maps to `UserRegisteredEvent`, serializes to JSON, and persists an `OutboxEvent` with `PENDING` status
5. **Domain event publishing** — Publishes `UserRegisteredDomainEvent` via Spring's `ApplicationEventPublisher` to trigger the `ImmediateOutboxSender` after commit

The entire flow runs within a `@Transactional` boundary.

Broker-specific concerns such as payload validation, deterministic event identity, duplicate detection, and
`ProcessedEventsRepository` writes are handled by `RabbitMQListener`, not by `UserService`.

#### `UserNodeService`

Handles synchronization of user data to the **Neo4j secondary database**:

1. **Boundary validation** — Verifies the synchronization command contains the required identifiers
2. **Node existence guard** — Avoids creating or deleting duplicate graph nodes
3. **Node creation/deletion** — Persists via `UserNodeRepository`

Also runs within a `@Transactional` boundary.

## Packages Used

| Package | Purpose |
|---------|---------|
| `org.springframework.stereotype.Service` | Service registration |
| `org.springframework.transaction.annotation.Transactional` | Transaction management |
| `org.springframework.context.ApplicationEventPublisher` | Domain event dispatch |
| `lombok.RequiredArgsConstructor` | Constructor injection |
| `lombok.extern.slf4j.Slf4j` | Logging |

Internal dependencies:
- `shared.infrastructure.entity` — `OutboxEvent`
- `shared.infrastructure.repository` — `OutboxEventRepository`
- `shared.infrastructure.mapper` — `JsonMapper`
- `user.domain.model` — `User` and value objects
- `user.infrastructure.mapper` — `UserEventMapper`
- `user.infrastructure.repository` — `UserRepository`, `UserNodeRepository`

## Why

- **Command pattern**: Decouples the "what to do" (command data) from "how it arrives" (RabbitMQ, HTTP, etc.). The application layer never knows about transport.
- **Listener-owned idempotency**: Duplicate RabbitMQ deliveries are handled in the infrastructure listener so the application services remain transport-agnostic.
- **Transactional outbox**: The user and outbox event are persisted in the **same transaction**, guaranteeing atomicity. The domain event published via `ApplicationEventPublisher` only triggers after the transaction commits.
- **Two-database sync**: `UserService` writes to PostgreSQL (source of truth), and `UserNodeService` writes to Neo4j (graph database for social relationships). This separation allows independent scaling and failure handling.
