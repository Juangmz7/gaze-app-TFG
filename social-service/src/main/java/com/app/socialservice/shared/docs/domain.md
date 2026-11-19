# Shared - Domain Layer

## What it does
The shared domain layer contains common domain abstractions and exceptions used across bounded contexts.

## Key Design Choices
- **Standardized Domain Event Contract**: `DomainEvent` gives domain events a common identifier shape. Integration event contracts such as `EventMessage` live in shared infrastructure because they are transport-facing messages.
- **Shared Exception Hierarchy**: `DomainException` is the base for domain rule violations, and `UserNotFoundException` is reused where user existence is a cross-context precondition.
- **Keyset pagination**: `KeysetCursor` is the `(createdAt, id)` sort key of the last row of a page, sent to clients as an opaque base64url token; `decode` accepts only the exact token `encode` issues (else `InvalidCursorException` -> 400 `invalid_cursor`). `PageLimit` is 1..50, default 20 (else `InvalidPageLimitException` -> 400 `invalid_limit`). `KeysetPage` (shared application) builds a page from `limit + 1` rows. Any list ordered by `created_at DESC, id DESC` reuses them.

## Components
- `DomainEvent`
- `DomainException`
- `UserNotFoundException`, `InvalidCursorException`, `InvalidPageLimitException`
- `KeysetCursor`, `PageLimit` (`pagination`); `KeysetPage` lives in `shared/application/pagination`
