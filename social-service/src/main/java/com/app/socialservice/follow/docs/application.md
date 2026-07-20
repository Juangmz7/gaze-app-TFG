# Follow - Application Layer

## What it does
Orchestrates follow/unfollow use cases. Relational state is the source of truth, while Neo4j graph relationships are maintained from follow integration events.

## Key Design Choices (For New Developers)
- **Command-Driven**: `FollowUserCommand` and `UnfollowUserCommand` handle the inputs cleanly.
- **Relational Source of Truth**: `FollowService` writes and reads relationship state through `FollowRepository`.
- **Graph Projection**: `FollowNodeService` applies published follow/unfollow events to Neo4j.
- **Guard Clauses**: Application services enforce cross-domain rules, including self-follow prevention, user existence checks, and block checks in both directions.
- **Idempotent Operations**: Following an already active relationship returns the existing relationship, unfollowing a missing active relationship returns success, and removed relationships can be reactivated.
- **Transactional Outbox**: Follow and unfollow writes create `UserFollowedEvent` or `UserUnfollowedEvent` outbox records before publishing domain events.

## Components
- `FollowService`, `FollowNodeService`
- `FollowUserCommand`, `UnfollowUserCommand`
- `FollowRepository`, `FollowGraphRepository`
