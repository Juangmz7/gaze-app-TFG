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
| `POST /api/feed/disconnect` | 202 | Records the end of the session on the same document, even if the client closes right away. |
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
| `collabId`, `collab.title`, `collab.status` | Null for a `BASIC` post. |
| `createdAt` | The post's creation date. |
| `media[]` | Sorted by `order`: `id`, `url`, `thumbnailUrl` (nullable), `mediaType` (`IMAGE` or `VIDEO`), `order` (1-based), `durationMs` (null for an `IMAGE`), `taggedUsers`. |
| `coverThumbnailUrl` | The first element's thumbnail, or its `url` if it is an `IMAGE` with none. Null otherwise. |
| `durationMs` | Sum of the `VIDEO` durations. Null when there is no video. |
| `stats.likeCount`, `stats.commentCount`, `stats.shareCount` | Current values. |
| `viewer.liked`, `viewer.shared`, `viewer.followingCreator` | The caller's state. |

Nothing populates the stored feed in production yet: the refill and its triggers arrive with the recommendation-service binding.

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
| `JWT_AUDIENCE` | required | Audience tokens must carry. |
| `FEED_DEFAULT_PAGE_SIZE` | `15` | Page size when the request has no `limit`. |
| `FEED_MAX_PAGE_SIZE` | `15` | Largest `limit` accepted. |
| `FEED_MAX_READS_PER_CLAIM` | `3` | How many times a claim reads further to replace dropped items. |
| `FEED_RETENTION_HOURS` | `168` | How long a served feed unit is kept. |
| `FEED_DISCONNECT_WRITE_TIMEOUT_MS` | `5000` | Time limit of the disconnect write. |

## Tests
`./test.sh` from `post-query-service/`. It needs Docker: integration tests use Testcontainers for MongoDB and RabbitMQ.
