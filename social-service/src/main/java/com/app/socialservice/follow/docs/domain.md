# Follow — Domain Layer

## What it does

The follow domain layer defines the core business model for the **Follow** relationship between users. This is currently a minimal implementation establishing the domain foundation for the follow feature.

## Package Structure

```
follow/domain/
└── model/
    └── Follow.java
```

## Components

### `Follow` (Domain Model)

Represents a directed follow relationship between two users:

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `followerId` | `UserId` | No | The user who is following |
| `followedId` | `UserId` | No | The user being followed |
| `createdAt` | `Instant` | No | When the follow was created |

Constructor requires `(UserId followerId, UserId followedId)`. The `createdAt` field is expected to be set externally.

> **Note**: This model reuses the `UserId` value object from the `user` domain, ensuring consistent user identity validation across modules.

## Packages Used

| Package | Purpose |
|---------|---------|
| `java.time.Instant` | Temporal tracking |
| `user.domain.model.valueobj.UserId` | User identity (shared value object) |

> No Spring or infrastructure dependencies.

## Why

- **Separate module**: The follow feature lives in its own module (`follow/`) rather than inside `user/`, following the principle of modular domain design. This allows the follow domain to evolve independently.
- **Reused value objects**: `UserId` is shared across modules to enforce the same identity constraints. This avoids duplication while maintaining type safety.
- **Directed relationship**: The model explicitly captures directionality (`follower` → `followed`), which maps naturally to the Neo4j graph model that will be used for social queries.
