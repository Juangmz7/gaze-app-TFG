# User — Domain Layer

## What it does

The domain layer defines the core business model for a **User** within the social service. It contains the `User` aggregate root, self-validating value objects, domain events, enums, and domain-specific exceptions. This layer has **zero infrastructure dependencies** — it expresses pure business rules.

## Package Structure

```
user/domain/
├── enums/
│   └── UserAccountStatus.java
├── events/
│   └── UserRegisteredDomainEvent.java
├── exception/
│   ├── InvalidEmailException.java
│   ├── InvalidProfilePictureUrlException.java
│   ├── InvalidUserIdException.java
│   └── InvalidUsernameException.java
└── model/
    ├── User.java
    └── valueobj/
        ├── Email.java
        ├── ProfilePictureUrl.java
        ├── UserId.java
        └── Username.java
```

## Components

### `User` (Aggregate Root)

The main domain entity. It encapsulates user identity and profile data using value objects:

| Field | Type | Nullable | Notes |
|-------|------|----------|-------|
| `id` | `UserId` | No | Wraps `UUID`, validated non-null |
| `username` | `Username` | No | 3–30 chars, non-blank |
| `email` | `Email` | No | RFC 5322 simplified regex |
| `pictureUrl` | `ProfilePictureUrl` | Yes | HTTP/HTTPS URL, optional |
| `accountStatus` | `UserAccountStatus` | No | Defaults to `ACCEPTED` |
| `createdAt` | `Instant` | No | Set externally |
| `updatedAt` | `Instant` | Yes | Set on updates |

Constructor requires `(UserId, Username, Email)` — the minimum data needed to create a valid user.

### Value Objects

All value objects are Java `record`s with **compact constructors** that enforce invariants at creation time:

- **`UserId`** — Wraps `UUID`. Rejects `null`.
- **`Username`** — Wraps `String`. Rejects null/blank and enforces length between 3 and 30 characters.
- **`Email`** — Wraps `String`. Rejects null/blank and validates against a simplified RFC 5322 regex pattern.
- **`ProfilePictureUrl`** — Wraps `String`. Rejects null/blank and ensures the URL uses `http` or `https` scheme via `URI.create()`.

### `UserAccountStatus` (Enum)

Represents the lifecycle state of a user account:

- `ACCEPTED` — Active, normal state (default on creation)
- `DELETED` — Soft-deleted account
- `BANNED` — Account suspended by moderation

### `UserRegisteredDomainEvent`

A domain event record implementing `DomainEvent`:

| Field | Type | Description |
|-------|------|-------------|
| `id` | `UUID` | Unique event identifier (matches outbox event ID) |
| `userId` | `UserId` | The registered user's ID |
| `occurredOn` | `Instant` | Timestamp of the event |

Used to trigger the `@TransactionalEventListener` in the outbox sender after a user registration transaction commits.

### Exceptions

All domain exceptions extend `RuntimeException` (unchecked) and carry a descriptive message:

- `InvalidUserIdException`
- `InvalidUsernameException`
- `InvalidEmailException`
- `InvalidProfilePictureUrlException`

## Packages Used

| Package | Purpose |
|---------|---------|
| `java.util.UUID` | User identity |
| `java.time.Instant` | Temporal fields |
| `java.util.regex.Pattern` | Email validation |
| `java.net.URI` | URL validation |

> No Spring or infrastructure dependencies.

## Why

- **Value objects with self-validation**: Enforces invariants at construction time, making it impossible to create invalid domain objects. This follows DDD tactical patterns.
- **Records for value objects and events**: Immutability by default, less boilerplate, structural equality.
- **No Lombok on domain**: The domain layer avoids Lombok to keep it explicit and framework-independent.
- **Unchecked exceptions**: Domain violations are programming errors, not recoverable conditions — using `RuntimeException` avoids cluttering every call site with try/catch.
- **`DomainEvent` interface**: Decouples the domain from infrastructure event dispatch — the domain only declares *what happened*, not *how* to publish it.
