# Post — Infrastructure Layer

## What it does
Handles infrastructure integration for the Post domain. Currently, this module only contains incoming event definitions from external services.

## Key Design Choices (For New Developers)
- **Event-Driven Integration**: This service doesn't manage posts directly, but it needs to know when posts are created or deleted (e.g., to update user stats or timelines). We define DTOs (`PostCreatedEvent`) to deserialize these messages from RabbitMQ.
- **Evolutionary Architecture**: As the domain grows, Application and Domain layers will be added. Starting with infrastructure events allows the service to react to the ecosystem immediately.

## Components
- `PostCreatedEvent`, `PostDeletedEvent`
