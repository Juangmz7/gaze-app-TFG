# Recommendation Service

## Overview
This service is responsible for generating personalized feeds and recommendations for users based on their social graph and interactions. It is built using Python, FastStream and grpc.aio, following a **Domain-Driven Design (DDD)** and **Hexagonal Architecture** approach where applicable.

## Packages
The service consists of several key modules:
- **Pipeline**: The recommendation pipeline (retrieval, enrichment, ranking, reranking) and the bounded worker pool that runs it off the event loop.
- **grpc_server**: The `GetRecommendedFeed` server and its health service. `src/recommendation/v1` holds the generated stubs.
- **User / Follow / Block / Post**: Read models and local domains to synchronize state from other services.
- **RabbitMQ**: Message broker integration for consuming events from `social-service` and `post-command-service`.
- **Observability**: Logging and tracing configurations.
- **Shared**: Common utilities.

## Key Design Choices
1. **Python & RabbitMQ**: Chosen for its ecosystem of data science and machine learning libraries, along with high-performance asynchronous API capabilities.
2. **Event Consumption**: It maintains its own read models of users, posts, and follows by listening to RabbitMQ events.
3. **Hexagonal Concepts**: Uses `model`/`entity`, `usecase`/`command`, and `repository` patterns to separate business logic from infrastructure concerns.
4. **Transactional Outbox**: Outgoing feed events (`RecommendedPostSent`, `TrendingPostSent` on `x.feed.events`) are written to `outbox_events` with `OutboxPublisher` inside the same transaction as any related state. Both `OutboxPublisher` and `OutboxRelayWorker` depend on the `OutboxEventRepository` abstraction (`SqlAlchemyOutboxEventRepository` holds the SQL), never on a SQLAlchemy session. Once the transaction commits, `OutboxPublisher` notifies `OutboxRelayWorker` through an `after_commit` callback and the worker publishes that event right away. Its periodic batch (every 4 s, an `asyncio` task started with the FastStream app) is the safety net: it claims rows with `FOR UPDATE SKIP LOCKED`, publishes them with publisher confirms and marks them `PROCESSED` after the broker ack; failures retry and become `FAILED` after 10 attempts, unroutable messages are logged and marked processed, and processed rows older than 7 days are deleted. The ML pipeline itself runs outside any write transaction.
5. **Schema**: Alembic revisions create every table in its final shape (there is no production data yet); later revisions that used to alter tables are kept as no-ops so the revision chain stays intact.

6. **Feed over two transports**: both run the same pipeline, through `RecommendedFeedUseCase`, in the same `PipelineWorkerPool` (`PIPELINE_WORKER_POOL_SIZE`, default 2), and return post ids best first: up to 30, 28 ranked plus 2 cold start. Fewer or none is a valid result. The two transports differ in who waits:
   - **gRPC** (`GetRecommendedFeed`, port `GRPC_PORT`, default 50051; contract in `proto/recommendation/v1/recommendation.proto`): used only when a user ran out of items. It writes nothing, so a retried call is safe. When every worker is busy it answers `RESOURCE_EXHAUSTED` right away. Malformed ids are `INVALID_ARGUMENT`, a passed deadline is `DEADLINE_EXCEEDED` without starting the pipeline, unexpected errors are `INTERNAL`, and no candidates is OK with an empty list. The correlation id travels in the `x-correlation-id` metadata and the trace context in `traceparent`. Any id of 1 to 128 printable ASCII characters without spaces is kept, so the logs of both services match; a missing or unusable one is replaced by a generated id without failing the call. Only the RabbitMQ `correlationId` has to be a UUID, because it is the lease token.
   - **RabbitMQ**: `UserFeedExhaustedEvent` on `x.feed.events` / `rk.post.feed.exhausted`, consumed from its own queue `q.recommendation-service.feed` (DLQ `-dlq`, `FEED_CONSUMER_PREFETCH` at a time per replica, default 1, so one worker stays free for gRPC). `FeedEventListener` opens no transaction around the handler. `ExhaustPostFeedUsecase` checks idempotency, runs the pipeline outside any transaction, then one short transaction marks the event processed and writes `RecommendedPostSent` to the outbox (`x.feed.events` / `rk.post.feed.recommended.sent`), with the request's `correlationId`, which post-query-service uses as its lease token, and also when the list is empty. Malformed payloads go straight to the DLQ; pipeline failures use the retry middleware.
7. **Excluded posts and new users**: `excludedPostIds` are removed while retrieving, before counting the target, and cold start skips them too. A user without semantic signal, or with no features row yet (post-query-service can ask before `UserRegisteredEvent` was consumed), gets the new-user retrieval (popular and random), because personalised retrieval has no embedding to compare with. Cold start is attempted even with no candidates.
8. **Startup**: `rabbitmq.config.connection:app` is the entrypoint. Importing it registers the user, post and feed subscribers (`rabbitmq/config/subscribers.py`) and builds the `Container`, which loads the embedding model. The topology is declared in `on_startup`, before the broker starts, because the subscribers open existing queues; it also removes the old `rk.post.feed.exhausted` binding from the post queue. The gRPC server and the outbox relay start in `after_startup`.

## Regenerating the gRPC stubs
From `recommendation-service/`, after changing the proto (a test fails when the committed stubs are stale):

```bash
python -m grpc_tools.protoc -I ../proto --python_out=src --pyi_out=src --grpc_python_out=src ../proto/recommendation/v1/recommendation.proto
```

The stubs are committed under `src/`, so the image build does not need the repo-root `proto` directory. Their package is `recommendation.v1`; nothing here may be named `grpc`, which would shadow the library.

Explore the `docs` folder inside each package for detailed documentation.
