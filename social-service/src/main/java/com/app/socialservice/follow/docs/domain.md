# Follow - Domain Layer

## What it does
Defines the core business model for the **Follow** relationship between users.

## Key Design Choices (For New Developers)
- **Modular Domain Design**: Follow logic lives here rather than inside the User domain. This prevents the User aggregate from becoming a bloated "God class".
- **Shared Value Objects**: It reuses `UserId` from the User domain to maintain type safety without duplication.
- **Directed Graph Intuition**: The model captures a directed relationship (`followerId` -> `followedId`), aligning perfectly with how it will be stored in Neo4j.
- **Status-Aware Relationship**: Infrastructure persists follow status so active, removed, and blocked relationships can be handled idempotently without losing history.

## Components
### Aggregate Root
- `Follow`
### Events & Exceptions
- `UserFollowedDomainEvent`, `UserUnfollowedDomainEvent`
- `SelfFollowNotAllowedException`, `SelfUnfollowNotAllowedException`, `FollowBlockedException`
