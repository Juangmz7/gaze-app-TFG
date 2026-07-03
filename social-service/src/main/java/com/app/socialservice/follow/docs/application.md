# Follow — Application Layer

## What it does
Orchestrates follow/unfollow use cases, updating both relational state and the social graph.

## Key Design Choices (For New Developers)
- **Command-Driven**: `FollowUserCommand` and `UnfollowUserCommand` handle the inputs cleanly.
- **Graph & Relational Sync**: The layer uses both `FollowRepository` (for relational source-of-truth) and `FollowGraphRepository` (for Neo4j graph operations).
- **Guard Clauses**: Application services enforce cross-domain rules (e.g., checking if a user is blocked before allowing a follow).

## Components
- `FollowService`, `FollowNodeService`
- `FollowUserCommand`, `UnfollowUserCommand`
- `FollowRepository`, `FollowGraphRepository`
