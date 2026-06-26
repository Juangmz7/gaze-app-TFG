# User — Infrastructure Layer

## What it does

The infrastructure layer provides the concrete implementations and adapters that connect the user domain and application layers to external systems: **PostgreSQL** (via JPA), **Neo4j** (via Spring Data Neo4j), **RabbitMQ** (via AMQP), and **MapStruct** mappers.

## Package Structure

```
user/infrastructure/
├── entity/
│   ├── UserBioEmbeddable.java
│   ├── UserEntity.java
│   └── UserNode.java
├── events/
│   ├── UserBioEventPayload.java
│   ├── UserRegisteredEvent.java
│   └── UserRegisteredFromAuthEvent.java
├── mapper/
│   ├── UserEventMapper.java
│   └── UserRegisterCommandMapper.java
├── rabbitmq/
│   └── UserRegisteredEventPublisher.java
└── repository/
    ├── JpaUserRepository.java
    ├── UserNodeRepository.java
    └── UserRepositoryImpl.java
```

## Components

### Entities

#### `UserEntity`

A JPA `@Entity` mapped to the `users` table in PostgreSQL. Mirrors the data structure of the domain `User` model, using native Java types (`UUID`, `String`, `Instant`) and JPA annotations (`@Column`, `@Enumerated`, `@PrePersist`, `@PreUpdate`) for persistence. It embeds `UserBioEmbeddable`, storing `socialMedia` as PostgreSQL `jsonb`.

#### `UserBioEmbeddable`

Embeds optional user profile metadata inside `UserEntity`:

| Field | Type | Annotations | Description |
|-------|------|-------------|-------------|
| `description` | `String` | `@Column(name = "description")` | Free-text profile description |
| `socialMedia` | `Map<String, String>` | `@JdbcTypeCode(SqlTypes.JSON)` + `@Column(columnDefinition = "jsonb")` | Social handles serialized as PostgreSQL `jsonb` |

#### `UserNode`

A Neo4j `@Node("User")` entity representing a user in the graph database. Used for social relationship queries (follows, blocks).

| Field | Type | Annotations | Description |
|-------|------|-------------|-------------|
| `id` | `UUID` | `@Id` | Mirrors the PostgreSQL user UUID |

Uses Lombok `@Builder`, `@AllArgsConstructor`, `@NoArgsConstructor`, `@Getter`, `@Setter`.

### Events (DTOs)

#### `UserRegisteredEvent`

The outgoing event published to RabbitMQ after a user is registered. Implements `EventMessage`:

| Field | Type | Description |
|-------|------|-------------|
| `id` | `UUID` | Event identifier |
| `correlationId` | `UUID` | Correlation ID for tracing |
| `occurredAt` | `Instant` | Timestamp |
| `userId` | `UUID` | Registered user's ID |
| `username` | `String` | Username |
| `email` | `String` | Email |
| `bio` | `UserBioEventPayload` | Optional bio payload mirrored from PostgreSQL |

#### `UserRegisteredFromAuthEvent`

The incoming event from the **auth-service** via Keycloak. Matches the Keycloak event admin webhook structure:

| Field | Type | Description |
|-------|------|-------------|
| `time` | `Long` | Epoch milliseconds |
| `type` | `String` | Event type |
| `realmId` | `String` | Keycloak realm |
| `userId` | `String` | Keycloak user ID |
| `details` | `Details` | Nested record with `username`, `email`, etc. |

### Mappers (MapStruct)

#### `UserEventMapper`

Maps domain `User` + metadata → user events. Extracts value object inner values (e.g. `user.id.value`, `user.username.value`) and maps `UserBio` into the nested `UserBioEventPayload`.

#### `UserRegisterCommandMapper`

Maps `UserRegisteredFromAuthEvent` → `UserRegisterCommand`. Converts:
- `event.userId` (String) → `UUID`
- `event.time` (Long epoch millis) → `Instant`
- `event.details.username` / `event.details.email` → flat command fields

### Repositories

#### `JpaUserRepository`

```java
public interface JpaUserRepository extends JpaRepository<UserEntity, UUID>
```

Spring Data JPA repository for the `UserEntity`.

#### `UserRepositoryImpl`

Implements the application layer `UserRepository`. It acts as an adapter, injecting `JpaUserRepository` and `UserMapper` to handle the conversion between domain models (`User`) and persistence models (`UserEntity`) before and after saving to the database.

#### `UserNodeRepository`

```java
public interface UserNodeRepository extends Neo4jRepository<UserNode, UUID>
```

Spring Data Neo4j repository for the `UserNode` graph entity.

### Publishers

#### `UserRegisteredEventPublisher`

Implements the `EventPublisher` strategy interface. Publishes `OutboxEvent` payloads to RabbitMQ:

- **`supports(String eventType)`** — Returns `true` for `"UserRegisteredEvent"`
- **`publish(OutboxEvent)`** — Sends the JSON payload to the configured exchange and routing key

Uses `RabbitTemplate` and reads exchange/routing key from `RabbitMQProperties`.

## Packages Used

| Package | Purpose |
|---------|---------|
| `org.springframework.data.jpa.repository.JpaRepository` | PostgreSQL persistence |
| `org.springframework.data.neo4j.repository.Neo4jRepository` | Neo4j persistence |
| `org.springframework.data.neo4j.core.schema.Node` | Neo4j entity mapping |
| `org.springframework.amqp.rabbit.core.RabbitTemplate` | RabbitMQ message sending |
| `org.mapstruct.Mapper` | Compile-time object mapping |
| `lombok.*` | Boilerplate reduction |

Internal dependencies:
- `shared.infrastructure.events.EventMessage` — Event contract
- `shared.infrastructure.entity.OutboxEvent` — Outbox entity for publishing
- `shared.infrastructure.rabbitmq.publisher.EventPublisher` — Strategy interface
- `shared.infrastructure.rabbitmq.config.RabbitMQProperties` — Exchange/routing key config

## Why

- **MapStruct mappers**: Compile-time code generation is safer and faster than reflection-based mapping. Explicit `@Mapping` annotations document the transformation clearly.
- **Strategy pattern for publishers**: `EventPublisher` interface + `supports()` method allows adding new event types without modifying existing code (Open/Closed Principle). The `ImmediateOutboxSender` and `OutboxRetryWorker` iterate over all publishers dynamically.
- **Dual database repositories**: PostgreSQL serves as the source of truth for relational data, while Neo4j handles graph queries for social relationships. Each has its own repository and entity.
- **Keycloak event structure**: `UserRegisteredFromAuthEvent` matches the webhook payload from Keycloak, enabling seamless deserialization by Jackson.
