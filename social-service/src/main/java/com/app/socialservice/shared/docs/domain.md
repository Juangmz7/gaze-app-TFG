# Shared — Domain Layer

## What it does
The shared domain layer contains common abstractions and interfaces utilized across all bounded contexts (e.g., `EventMessage`, `DomainEvent`).

## Key Design Choices (For New Developers)
- **Standardized Event Contracts**: By having common interfaces, we ensure that every domain event across the system conforms to a standard structure (e.g., having an ID, correlation ID, and timestamp).
- **Code Reuse**: Shared exceptions and base classes reduce boilerplate in individual domains.
