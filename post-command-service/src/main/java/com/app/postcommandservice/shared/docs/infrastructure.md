# Shared - Infrastructure Layer

## What it does
Contains cross-cutting infrastructure implementations: security, API error handling, datasource configuration, Redis caching, RabbitMQ configuration, listener support, observability, and the transactional outbox engine.

## Key Design Choices
- **Centralized Outbox Processing**: The outbox relay lives here. `OutboxDestinationResolver` stores each row's exchange and routing key at insert time (from the `EventPublisher` that supports its event type), inside the business transaction. `OutboxRelay` claims rows one at a time (`FOR UPDATE SKIP LOCKED`, safe with several instances), publishes them as persistent JSON with publisher confirms and marks them `PROCESSED` only after the broker ack. Failures go back to `PENDING` with `attempts`/`last_error` and stop the batch to keep ordering; after 10 attempts the row is `FAILED`. A claim left in `PROCESSING` (its relay died) is reclaimed once its 60 s lock expires; a failure is only recorded by the claim that still owns the row (matched by `attempts`), so a relay whose lock expired cannot undo a newer claim or a `PROCESSED` row. Unroutable messages (acked but returned) are logged and marked processed. `ImmediateOutboxSender` publishes each row right after commit through the same path; a daily job deletes processed rows older than 7 days. Delivery is at-least-once, so consumers deduplicate by message id; when a confirmed row cannot be marked `PROCESSED`, the relay logs that it will be republished as a duplicate once its lock expires.
- **Scheduled Job Locking**: `ShedLockConfig` enables ShedLock (`@EnableSchedulerLock`, `JdbcTemplateLockProvider` with the database clock) so a `@SchedulerLock` job runs on one instance per interval. Locks live in the `shedlock` table; `ShedLockEntity` maps it only so `ddl-auto` creates it in tests and validates it in prod. Used by the post media cleanup job (`PostMediaCleanupService`, hourly at minute 15), which fails `PENDING` posts older than `posts.media.upload-window` with `UPLOAD_EXPIRED`, deletes the blobs of expired and failed uploads outside any transaction and sets `posts.media_purged_at`.
- **Global Error Handling**: `ApiExceptionHandler` intercepts exceptions and formats standard API error responses, keeping controllers clean.
- **Security Configuration**: Centralized Spring Security setup for JWT validation. `KeycloakRealmRoleConverter` maps Keycloak `realm_access.roles` to `ROLE_*` authorities (alongside the default `SCOPE_*` ones); `SecurityConfig` restricts the report lists to `ROLE_ADMIN` and `CustomAccessDeniedHandler` answers those 403s with the standard `ApiErrorResponse` body.
- **Keyset Pagination**: `KeysetCursorCodec` encodes/decodes the opaque `cursor` (unpadded base64url of `<instant>|<uuid>`, the last row's `(created_at, id)`); malformed cursors raise `InvalidCursorException` (`invalid_cursor`). `PageLimit` (shared domain) parses `limit` (1..50, default 20) and raises `InvalidPageLimitException` (`invalid_limit`).
- **Error Codes**: generic codes are UPPER_CASE; feature-specific codes are snake_case on the wire through `@JsonProperty` (`post_not_found`, `comment_not_found`, `cannot_report_own_content`, `invalid_cursor`, `invalid_limit`). Unreadable bodies (malformed JSON, unknown enum, malformed UUID) answer 400 `VALIDATION_ERROR`.
- **RabbitMQ Schema Ownership**: `RabbitMQConfig` declares exchanges, queues, dead-letter queues, bindings, JSON conversion, retry advice, and listener container settings from `RabbitMQProperties`.
- **Idempotent Message Handling**: `AbstractRabbitMQListenerSupport` provides duplicate detection and processed-event recording through `ProcessedEventsRepository`.
- **Multi-Store Configuration**: JPA/PostgreSQL, and Redis configuration are centralized here so feature modules depend on repository interfaces and Spring beans rather than connection details.

## Components
- `DatasourceConfig`, `JpaConfig`, `RedisConfig`
- `SecurityConfig`, `SecurityUtils`, `CustomAuthenticationEntryPoint`, `CustomAccessDeniedHandler`, `KeycloakRealmRoleConverter`, `ApiExceptionHandler`, `ApiErrorCode`
- `KeysetCursorCodec`
- `RabbitMQConfig`, `RabbitMQProperties`, `EventPublisher`
- `AbstractRabbitMQListenerSupport`
- `OutboxEvent`, `ProcessedEvent`, `OutboxEventRepository`, `ProcessedEventsRepository`
- `ShedLockConfig`, `ShedLockEntity`
- `ImmediateOutboxSender`, `OutboxRelay`, `OutboxDestinationResolver`, `OutboxDestination`
- `EventMessage`, `JsonMapper`
