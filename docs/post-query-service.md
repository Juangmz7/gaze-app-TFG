# post-query-service

## What it does
Read side of the platform, written in Go. It consumes the events of `post-command-service` and `social-service` from RabbitMQ, keeps its own read models in MongoDB, and serves the home feed over HTTP.

## Packages used
- **`net/http`:** `ServeMux` with method patterns.
- **`go.mongodb.org/mongo-driver/v2`:** MongoDB driver (replica set, because transactions need it).
- **`ThreeDotsLabs/watermill` and `watermill-amqp`:** RabbitMQ consumers, with a dead-letter queue per queue.
- **`common-packages/go-utils`:** JWT validation (`auth`), error bodies (`common`), HTTP lifecycle (`server`). Resolved through `go.work`.

## Feed API
Every endpoint needs a valid `Authorization: Bearer` token. The user always comes from the token's subject, which must be a UUID; otherwise the answer is 401. The `X-Correlation-Id` request header is used when present, generated otherwise, and returned in the response.

| Endpoint | Answer | Effect |
| --- | --- | --- |
| `POST /api/feed/heartbeat` | 204 | Marks the user present and due a prefetch, in their single `user_feed_sessions` document. |
| `POST /api/feed/disconnect` | 202 | Records the end of the session on the same document, even if the client closes right away, and asks for the feed to be prepared. Never waits for the broker or recommendation-service. |
| `GET /api/feed?lastSeenId=<postId>&limit=<n>` | 200 | Returns the next page. |

Both query parameters are optional. A malformed `lastSeenId`, or a `limit` that is not a positive integer up to the maximum, is a 400.

```json
{"items": [], "lastSeenId": null, "hasMore": false}
```

- **Without `lastSeenId`** the page starts at the lowest unserved position, so a reopened app does not replay old items. The same happens, with a logged warning, when `lastSeenId` does not resolve for that user.
- **With `lastSeenId`** the page starts right after it, and repeating a cursor returns the same page.
- **A short page is valid.** Items whose post or creator is gone, or whose creator has a block with the viewer in either direction, are left out, and the service reads further to fill the page within a bounded number of reads.

Each item has:

| Field | Notes |
| --- | --- |
| `postId`, `position` | Send `position` back as `feedPosition`, with `source=HOME_FEED`, in like and view commands. |
| `creator.userId`, `creator.username`, `creator.pictureUrl` | `pictureUrl` can be null. `username` is the only visible name. |
| `description`, `postType`, `postTags` | `description` can be null. `postType` is `BASIC` or `COLLAB`. |
| `collabId`, `collab.title`, `collab.status` | Null for a `BASIC` post. A post with several collabs serves the most recently opened one. |
| `createdAt` | The post's creation date. |
| `media[]` | Sorted by `order`: `id`, `url`, `thumbnailUrl` (nullable), `mediaType` (`IMAGE` or `VIDEO`), `order` (1-based), `durationMs` (null for an `IMAGE`), `taggedUsers`. |
| `coverThumbnailUrl` | The first element's thumbnail, or its `url` if it is an `IMAGE` with none. Null otherwise. |
| `durationMs` | Sum of the `VIDEO` durations. Null when there is no video. |
| `stats.likeCount`, `stats.commentCount`, `stats.shareCount` | Current values. |
| `viewer.liked`, `viewer.shared`, `viewer.followingCreator` | The caller's state. |

## How the feed gets filled
The feed is prepared ahead of time over RabbitMQ, through the transactional outbox:

1. A trigger asks for a prefetch: a disconnect, a user silent for 3 minutes (a background sweeper on every replica), a newly registered user, or a claim that leaves less than one page unserved.
2. Skipped when the user already has the maximum of unserved items. Otherwise one transaction takes the user's lease and stores `UserFeedExhaustedEvent` for `x.feed.events` with routing key `rk.post.feed.exhausted`, carrying `reason`, `currentFeedSize` and `excludedPostIds`. Only one trigger per user can hold the lease.
3. recommendation-service answers on `rk.post.feed.recommended.sent`, consumed from `q.post-query-service.feed`. Its `correlationId` is the lease token.
4. The units are stored and the lease is released, only if that token still holds it. The lease also expires by itself, which covers a crashed instance or a lost response.

The synchronous refill over gRPC, used when a claim finds nothing left, is not available yet: an exhausted feed answers an empty page and the claim's low watermark asks for more in the background.

## Configuration
| Variable | Default | Purpose |
| --- | --- | --- |
| `POST_QUERY_MONGO_URI` | required | MongoDB connection string. |
| `POST_QUERY_MONGO_DATABASE` | `post_query` | Database name. |
| `RABBITMQ_USER`, `RABBITMQ_PASSWORD` | required | Broker credentials. |
| `RABBITMQ_HOST`, `RABBITMQ_PORT` | `localhost`, `5672` | Broker address. |
| `HTTP_PORT` | `8080` | HTTP port. |
| `JWKS_URL` | required | Keycloak signing keys. They are fetched at startup, so a wrong value stops the service from starting. |
| `JWT_ISSUER` | required | Issuer tokens must carry: the public Keycloak URL, not the internal one. |
| `JWT_AUDIENCE` | required | Audience tokens must carry. `account` in every environment: Keycloak adds it because every user holds `account` client roles through the realm's default role. Removing those roles from the default role would make every feed request answer 401. |
| `FEED_DEFAULT_PAGE_SIZE` | `15` | Page size when the request has no `limit`. |
| `FEED_MAX_PAGE_SIZE` | `15` | Largest `limit` accepted. |
| `FEED_MAX_READS_PER_CLAIM` | `3` | How many times a claim reads further to replace dropped items. |
| `FEED_RETENTION_HOURS` | `168` | How long a served feed unit is kept. |
| `FEED_DISCONNECT_WRITE_TIMEOUT_MS` | `5000` | Time limit of the disconnect write and its prefetch request. |
| `FEED_INSTANCE_ID` | hostname | Owner recorded on a lease. |
| `FEED_PREFETCH_MAX_UNSERVED` | `60` | Unserved items at which every trigger skips. Not below `FEED_DEFAULT_PAGE_SIZE`. |
| `FEED_PREFETCH_EXCLUDED_IDS_CAP` | `200` | Most ids sent as `excludedPostIds`. Not below `FEED_PREFETCH_MAX_UNSERVED`. |
| `FEED_PREFETCH_TIMEOUT_MS` | `5000` | Time limit of one prefetch request. |
| `FEED_LEASE_TTL_MS` | `300000` | When an unanswered lease expires. Must exceed queue plus pipeline time. An estimate: the pipeline is not measured yet. |
| `FEED_LEASE_RELEASE_TIMEOUT_MS` | `5000` | Time limit of a lease release. |
| `FEED_LOW_WATERMARK_CONCURRENCY` | `16` | Background low watermark checks at once. |
| `FEED_SWEEP_INTERVAL_MS` | `60000` | Pause between sweeps. |
| `FEED_SWEEP_STALE_AFTER_MS` | `180000` | How old a last heartbeat must be for the sweeper to act. |
| `FEED_SWEEP_BATCH_SIZE` | `100` | Sessions read per batch by a sweep. |

## Tests
`./test.sh` from `post-query-service/`. It needs Docker: integration tests use Testcontainers for MongoDB and RabbitMQ.
