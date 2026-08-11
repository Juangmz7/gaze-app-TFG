# Shared - Domain Layer

## What it does
The shared domain layer contains common domain abstractions and exceptions used across bounded contexts.

## Key Design Choices (For New Developers)
- **Standardized Domain Event Contract**: `DomainEvent` gives domain events a common identifier shape. Integration event contracts such as `EventMessage` live in shared infrastructure because they are transport-facing messages.
- **Shared Exception Hierarchy**: `DomainException` is the base for domain rule violations, and `UserNotFoundException` is reused where user existence is a cross-context precondition.

## Components
- `DomainEvent`
- `DomainException`
- `UserNotFoundException`
