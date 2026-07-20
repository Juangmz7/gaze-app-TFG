# Block - Application Layer

## What it does
Orchestrates the blocking and unblocking use cases and coordinates their side effects on follow relationships.

## Key Design Choices (For New Developers)
- **Relational Source of Truth**: `BlockService` stores block relationships in PostgreSQL through `BlockRepository`.
- **Follow Side Effects**: Blocking marks bidirectional follow relationships as blocked. Unblocking removes bidirectional blocked follow relationships instead of restoring previous follows automatically.
- **Graph Projection**: `BlockNodeService` handles Neo4j cleanup triggered by block-created integration events.
- **Transactional Outbox**: Blocking and unblocking create `UserBlockedEvent` and `UserUnblockedEvent` outbox records before publishing domain events.
- **Guard Clauses**: The service prevents self-block/self-unblock and checks that the target user exists.

## Components
- `BlockService`, `BlockNodeService`
- `BlockUserCommand`, `UnblockUserCommand`
- `BlockRepository`
