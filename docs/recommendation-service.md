# Recommendation Service

## Overview
This service is responsible for generating personalized feeds and recommendations for users based on their social graph and interactions. It is built using Python and FastAPI, following a **Domain-Driven Design (DDD)** and **Hexagonal Architecture** approach where applicable.

## Packages
The service consists of several key modules:
- **Pipeline**: Machine learning or recommendation pipelines.
- **User / Follow / Block / Post**: Read models and local domains to synchronize state from other services.
- **RabbitMQ**: Message broker integration for consuming events from `social-service` and `post-command-service`.
- **Observability**: Logging and tracing configurations.
- **Shared**: Common utilities.

## Key Design Choices
1. **Python & RabbitMQ**: Chosen for its ecosystem of data science and machine learning libraries, along with high-performance asynchronous API capabilities.
2. **Event Consumption**: It maintains its own read models of users, posts, and follows by listening to RabbitMQ events.
3. **Hexagonal Concepts**: Uses `model`/`entity`, `usecase`/`command`, and `repository` patterns to separate business logic from infrastructure concerns.
4. **Transactional Outbox**: Outgoing feed events (`RecommendedPostSent`, `TrendingPostSent` on `x.feed.events`) are written to `outbox_events` with `OutboxPublisher` inside the same transaction as any related state. `OutboxRelayWorker` (an `asyncio` task started with the FastStream app) claims rows with `FOR UPDATE SKIP LOCKED`, publishes them with publisher confirms and marks them `PROCESSED` after the broker ack; failures retry and become `FAILED` after 10 attempts, unroutable messages are logged and marked processed, and processed rows older than 7 days are deleted. The ML pipeline itself runs outside any write transaction.
5. **Schema**: Alembic revisions create every table in its final shape (there is no production data yet); later revisions that used to alter tables are kept as no-ops so the revision chain stays intact.

Explore the `docs` folder inside each package for detailed documentation.
