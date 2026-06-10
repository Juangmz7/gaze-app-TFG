# Shared — Domain Layer

## What it does

The shared domain layer provides cross-cutting domain abstractions used by all modules in the social service. Currently it contains the `DomainEvent` interface.

## Package Structure

```
shared/domain/
└── events/
    └── DomainEvent.java
```

## Components

### `DomainEvent` (Interface)

A marker interface for all domain events in the service:

```java
public interface DomainEvent {
    UUID id();
}
```

All domain events must provide an `id()` — this ID is used by the `ImmediateOutboxSender` to look up the corresponding `OutboxEvent` after the transaction commits.

## Packages Used

| Package | Purpose |
|---------|---------|
| `java.util.UUID` | Event identification |

> No Spring or infrastructure dependencies.

## Why

- **Shared contract**: Having a common `DomainEvent` interface in the shared domain allows the outbox infrastructure to handle events from any module without knowing their concrete type.
- **Minimal interface**: Only requires `id()` — the minimum data needed to correlate domain events with their persisted outbox entries. Each module's event adds its own specific fields.
- **Framework independence**: The interface lives in the domain layer and uses only JDK types, keeping the domain clean.
