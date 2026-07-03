# Block — Domain Layer

## What it does
Defines the business logic for blocking and unblocking users.

## Key Design Choices (For New Developers)
- **Isolated Context**: Blocking is treated as its own domain. It dictates interactions in other domains (e.g., Follows are rejected if a Block exists).
- **Invariant Enforcement**: Value objects and exception handling (like `SelfBlockNotAllowedException`) prevent illogical states at the core level.

## Components
### Aggregate Root
- `Block`
### Events & Exceptions
- `UserBlockedDomainEvent`, `UserUnblockedDomainEvent`
- `SelfBlockNotAllowedException`, `SelfUnblockNotAllowedException`
