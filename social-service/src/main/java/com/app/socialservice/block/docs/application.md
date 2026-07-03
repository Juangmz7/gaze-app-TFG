# Block — Application Layer

## What it does
Orchestrates the blocking and unblocking use cases.

## Key Design Choices (For New Developers)
- **Dual Persistence**: Like other modules, `BlockService` writes to the relational DB, and `BlockNodeService` manages the Neo4j block relationships to accurately filter graph queries.
- **Transactional Outbox**: Blocking a user triggers domain events that are guaranteed to be published to RabbitMQ to notify other microservices.

## Components
- `BlockService`, `BlockNodeService`
- `BlockUserCommand`, `UnblockUserCommand`
- `BlockRepository`
