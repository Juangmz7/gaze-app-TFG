# User — Domain Layer

## What it does
The domain layer defines the core business model for a **User**. It contains the `User` aggregate root, self-validating value objects, and domain events.

## Key Design Choices (For New Developers)
- **Zero Infrastructure Dependencies**: This layer expresses pure business rules. You won't find Spring annotations or database imports here.
- **Self-Validating Value Objects**: We use Java `record`s with compact constructors (e.g., `UserId`, `Email`) to validate data upon creation. You cannot instantiate an invalid object, preventing bad data from entering the system.
- **Immutability**: Value objects and events are immutable by default, preventing unexpected side effects.
- **Unchecked Exceptions**: Domain errors (like `InvalidEmailException`) extend `RuntimeException`. We treat domain violations as programming errors, keeping code clean from verbose try/catch blocks.

## Components
### Aggregate Root
- `User`: Encapsulates user identity and profile data. 
### Value Objects
- `UserId`, `Username`, `Email`, `ProfilePictureUrl`, `UserBio`
### Events & Enums
- `UserRegisteredDomainEvent`, `UserAuthInfoUpdatedDomainEvent`, `UserDeletedDomainEvent`
- `UserAccountStatus` (ACCEPTED, DELETED, BANNED)
