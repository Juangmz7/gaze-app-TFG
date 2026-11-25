# post-query-service

## What it does
Read side of the platform, written in Go. It consumes the events of `post-command-service` and `social-service` from RabbitMQ, keeps its own read models in MongoDB, and serves the home feed (`/api/feed`), the following feed (`/api/feed/following`) and post, comment, profile grid, own tab (liked, history) and collab join request reads (`/api/content`) over HTTP, with each collab's join instructions shown only to its owner and accepted members.

## Packages used
- **`net/http`:** `ServeMux` with method patterns.
- **`go.mongodb.org/mongo-driver/v2`:** MongoDB driver (replica set, because transactions need it).
- **`ThreeDotsLabs/watermill` and `watermill-amqp`:** RabbitMQ consumers, with a dead-letter queue per queue.
- **`common-packages/go-utils`:** JWT validation (`auth`), error bodies (`common`), HTTP lifecycle (`server`). Resolved through `go.work`.
- **`google.golang.org/grpc` and `grpc-ecosystem/go-grpc-middleware/v2`:** the client of recommendation-service, with its retry interceptor.
- **`Azure/azure-sdk-for-go/sdk/storage/azblob` (`sas`, `service`) and `azidentity`:** the read SAS on media URLs, and the user delegation key in production. azblob is pinned at v1.8.0: v1.8.2 and later pull a newer gRPC than the workspace uses.

## Feed API
Every endpoint needs a valid `Authorization: Bearer` token. The user always comes from the token's subject, which must be a UUID; otherwise the answer is 401. The `X-Correlation-Id` request header is used when present, generated otherwise, and returned in the response.

| Endpoint | Answer | Effect |
| --- | --- | --- |
| `POST /api/feed/heartbeat` | 204 | Marks the user present and due a prefetch, in their single `user_feed_sessions` document. |
| `POST /api/feed/disconnect` | 202 | Records the end of the session on the same document, even if the client closes right away, and asks for the feed to be prepared. Never waits for the broker or recommendation-service. |
| `GET /api/feed?lastSeenId=<postId>&limit=<n>` | 200, or 503 with `Retry-After` | Returns the next page, building it first when nothing is left. |
| `GET /api/feed/following?cursor=&limit=` | 200 `{ items: PostView[], nextCursor }`, 400 `invalid_cursor` / `invalid_limit` | The Home "Following" tab (see "Following feed"). Touches nothing of the feed above. |

Both query parameters are optional. A malformed `lastSeenId`, or a `limit` that is not a positive integer up to the maximum, is a 400.

`hasMore` is true whenever the page has items, because the next claim can refill. It is false only when the page is empty because recommendation-service has nothing for the user. A page that is empty for any other reason is either `hasMore: true` or a 503, never the end of the feed:

| Situation | Answer |
| --- | --- |
| recommendation-service is failing, after its retries | 503 `feed_temporarily_unavailable`, `Retry-After` |
| Another build holds the lease and nothing appeared within the wait | 503 `feed_temporarily_unavailable`, `Retry-After` |
| It returned posts and none could be stored (not projected yet) | 503 `feed_temporarily_unavailable`, `Retry-After` |
| It returned no posts | 200, empty page, `hasMore: false` |

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
| `collab.pendingRequestCount` | Number of `PENDING` join requests of that collab, **only for its owner** (0 when none). Null for everyone else and for a `BASIC` post. See "Collab join requests". |
| `collab.joinInstructions` | Free-form onboarding text the collab's ADMIN wrote (a chat invite, a repository URL...), **only for its owner and its `ACCEPTED` members**. Null for everyone else (`PENDING`, `REJECTED`, `DELETED`, `LEFT`, `BANNED`, no membership), when the collab has none, and for a `BASIC` post. See "Collab join instructions". |
| `createdAt` | The post's creation date. |
| `media[]` | Sorted by `order`: `id`, `url`, `thumbnailUrl` (nullable), `mediaType` (`IMAGE` or `VIDEO`), `order` (1-based), `durationMs` (null for an `IMAGE`), `taggedUsers`. |
| `coverThumbnailUrl` | The first element's thumbnail, or its `url` if it is an `IMAGE` with none. Null otherwise. |
| `durationMs` | Sum of the `VIDEO` durations. Null when there is no video. |
| `stats.likeCount`, `stats.commentCount`, `stats.shareCount` | Current values. |
| `viewer.liked`, `viewer.shared`, `viewer.followingCreator` | The caller's state. |
| `viewer.collabRequestStatus` | The caller's membership of the post's collab: `PENDING`, `ACCEPTED`, `REJECTED`, `DELETED`, `LEFT` or `BANNED`. Null when the caller never asked to join it, and for a `BASIC` post. |

### Following feed
`GET /api/feed/following` is the Home "Following" tab: the posts of the users the caller follows, newest first (`createdAt`, then `postId`, both descending). Items are `PostView`s, exactly what `GET /api/content/posts/{postId}` answers for each post (see "Content API").

- **Separate from the recommended feed.** It reads the posts read model directly: no feed unit is read or marked served, no position is involved, the presence document is not touched and no prefetch is triggered. Opening the tab never consumes the recommended feed.
- **Who.** The caller's follows are read once per request (`user_follows`, most recent first, on `{ follower_user_id: 1, created_at: -1, followed_user_id: -1 }`). Above `FEED_FOLLOWING_MAX_FOLLOWS` (2000 by default) only the most recent follows are used, and that is logged. The caller's own posts, the posts of users in a block relation with the caller (either direction) and of users no longer projected are left out before the page is read, so they never take a place in it.
- **Empty.** A caller who follows nobody (or only hidden users) gets `{"items": [], "nextCursor": null}`.
- Keyset pagination as for the grids: `limit` 1 to 50 (default 20), opaque `cursor`, `nextCursor` null on the last page. A page reads the posts index of the profile grids (`{ user_id: 1, created_at: -1, post_id: -1 }`) with `user_id` in the followed ids, in groups of 200 ids: each group is one merge of index seeks that start at the cursor, so page N costs what page 2 costs. Neither key changes, so a post is never served twice, and a post published while the caller pages shows up on the next refresh, not further down.

### Media URLs
The media container is private, so every blob URL in a response carries a read-only SAS: `media[].url`, `media[].thumbnailUrl`, `coverThumbnailUrl` and `creator.pictureUrl`. Download them as they are, with no header. The shape does not change (`string | null`, null stays null).

- **Only the configured container is signed.** A URL of another host, account or container, or one that already has a query string, is returned unchanged.
- **Read only:** `sp=r`, `sr=b` (one blob), HTTPS only (`spr=https`), except against Azurite over plain HTTP (`spr=https,http`).
- **Stable within a day.** Time is cut into fixed windows of `MEDIA_READ_SAS_WINDOW` (24 h), counted from the Unix epoch, so UTC days by default. Every URL signed in one window is byte-identical for the same blob, so an image cache keyed on the URL keeps working, and it stays valid until the end of the next window: `st` = window start minus 5 minutes, `se` = window start plus two windows (48 h). A URL is valid between 24 h and 48 h after it was received.
- **Stored bare.** MongoDB only ever holds the bare URL; a URL is signed when the response is built, by the response mapper (`feedmapper.go`) through the shared signer in `internal/shared/infrastructure/mediasas`.
- **Keys.** Signing is a local HMAC, with no call per URL or per request. Azurite (dev, compose) signs with its well-known account key. Production holds no account key: it fetches a user delegation key through AKS Workload Identity once per window, valid exactly as long as that window's SAS, and keeps the previous one, which it signs with (still valid for the whole current window) if a fetch fails. A failed fetch is retried at most every 30 s. With neither key, the claim answers 500.

## Content API
Every read that is not the feed lives under `/api/content`, routed by the gateway to this service with the same token rules as the feed (valid `Authorization: Bearer`, user from the subject, `X-Correlation-Id` handled the same way).

| Endpoint | Answer |
| --- | --- |
| `GET /api/content/posts/{postId}` | 200 `PostView`, 400 `invalid_post_id`, 404 `post_not_found` |
| `GET /api/content/posts/{postId}/comments?cursor=&limit=` | 200 `{ items: Comment[], nextCursor }`, 400 `invalid_post_id` / `invalid_cursor` / `invalid_limit`, 404 `post_not_found` |
| `GET /api/content/posts/{postId}/comments/{commentId}/replies?cursor=&limit=` | 200 `{ items: Comment[], nextCursor }`, 400 `invalid_post_id` / `invalid_comment_id` / `invalid_cursor` / `invalid_limit`, 404 `post_not_found` / `comment_not_found` |
| `GET /api/content/users/{userId}/posts?cursor=&limit=` | 200 `{ items: GridItem[], nextCursor }`, 400 `invalid_user_id` / `invalid_cursor` / `invalid_limit`, 404 `user_not_found` |
| `GET /api/content/users/{userId}/reposts?cursor=&limit=` | same as `/posts` |
| `GET /api/content/me/liked?cursor=&limit=` | 200 `{ items: (GridItem & { likedAt })[], nextCursor }`, 400 `invalid_cursor` / `invalid_limit` |
| `GET /api/content/me/history?cursor=&limit=` | 200 `{ items: (GridItem & { viewedAt })[], nextCursor }`, 400 `invalid_cursor` / `invalid_limit` |
| `GET /api/content/collabs/{collabId}/requests?status=&cursor=&limit=` | 200 `{ items: { user: UserSummary, status, requestedAt }[], nextCursor }`, 400 `invalid_collab_id` / `invalid_status` / `invalid_cursor` / `invalid_limit`, 403 `not_collab_owner`, 404 `collab_not_found` |

`PostView` is a feed item without `position`, `collab.pendingRequestCount`, `collab.joinInstructions` and `viewer.collabRequestStatus` included. Its values come from the same read models and the same mapper as the feed, with `viewer` holding the caller's state and every media URL signed (see "Media URLs").

- `postId` must be a canonical UUID; anything else is a 400.
- A post that was never projected, was deleted or banned, whose creator is gone, or whose creator has a block with the caller in either direction, is the same 404 `post_not_found`, so a block is never revealed.
- Reading a post does not touch the feed: nothing is marked served.

### Comments
`Comment = { commentId, postId, author: UserSummary, content, replyTo: string | null, likeCount, replyCount, likedByMe, createdAt, updatedAt }`, where `UserSummary = { userId, username, pictureUrl: string | null }` is the same shape as a feed item's `creator`, with the picture signed.

- `/comments` lists the top-level comments of the post: most liked first, then most replied, then newest (`commentId` breaks the last ties). `/replies` lists the replies of one comment of that post, oldest first.
- Keyset pagination: `limit` is 1 to 50 (default 20); `nextCursor` is an opaque string to send back as `cursor`, and null on the last page. Every page costs the same, however deep. A cursor from one list is rejected by the other.
- The order uses counters that change, so a comment liked or replied to between two requests can move across the cursor and appear twice or not at all. Dedupe by `commentId`.
- Comments and replies written by a user who blocked the caller, or whom the caller blocked, are left out. The post rules are those of `GET /api/content/posts/{postId}` (same 404). A parent comment that is missing, of another post, or written across a block is 404 `comment_not_found`.
- `likeCount` and `replyCount` come from `rk.post.comment.like.created` / `.deleted` and from replies created or deleted; `likedByMe` from the caller's comment likes.

### Profile grids
`GridItem = { postId, coverThumbnailUrl: string | null, mediaType: 'IMAGE' | 'VIDEO', mediaCount, postType: 'BASIC' | 'COLLAB', collabStatus: 'OPEN' | 'CLOSED' | null, likeCount, viewCount, createdAt }`. `mediaType` is that of the first media item; `coverThumbnailUrl`, `postType` and `collabStatus` follow the same rules as a feed item (`coverThumbnailUrl` signed, `collabStatus` null without a collab); `createdAt` is the post's. `viewCount` is the post's number of views (`stats.view_count`), always a number, 0 for a post nobody viewed; the four lists (`/users/{userId}/posts`, `/reposts`, `/me/liked`, `/me/history`) carry it. Feed items and `PostView` do not (their `stats` are unchanged).

- `/posts` lists the user's posts, newest first; `/reposts` the posts the user shared, most recently shared first. Ties on the date are broken by `postId`, descending.
- Both are public to any signed-in user. A user who is unknown, deleted, or in a block relation with the caller (either direction) is the same 404 `user_not_found`.
- Keyset pagination as for the comments: `limit` 1 to 50 (default 20), opaque `cursor`, `nextCursor` null on the last page, every page an index seek on `{ user_id: 1, created_at: -1, post_id: -1 }` (on `posts` and on `post_shares`). The dates never change, so the cursor is stable.
- `/reposts` skips a share whose post no longer exists, and a post GetPost would not serve the caller (creator gone, or in a block relation with the caller). It reads on after the skipped shares to fill the page, at most 3 share reads per request; a page cut short by that has a `nextCursor` that resumes after the last share read.

### Own tabs: liked and history
Private tabs of the caller's own profile. The routes take no user id: the list is always the token subject's, so there is no way to read another user's likes or history.

- `/me/liked` lists the posts the caller liked, most recently liked first (`likedAt` is the like's date). It reads `post_likes` on `{ user_id: 1, created_at: -1, post_id: -1 }`.
- `/me/history` lists the posts the caller viewed, **one entry per post**, most recently viewed first (`viewedAt` is the latest view). It reads `view_history` on `{ user_id: 1, last_viewed_at: -1, post_id: -1 }`.
- Items are `GridItem`s (see "Profile grids") plus that date. Posts that no longer exist, whose creator is gone, or whose creator is in a block relation with the caller are skipped, and the page reads on to fill itself (at most 3 reads, like `/reposts`).
- Keyset pagination as for the grids: `limit` 1 to 50 (default 20), opaque `cursor`, `nextCursor` null on the last page, every page an index seek. A post viewed again while the caller pages moves to the top of the history: it is not served again further down, and shows up first on the next refresh.

**Viewing history projection.** `rk.post.viewed` (`PostViewedEvent` of post-command-service, already bound to `q.post-query-service.post`) is projected into `view_history { user_id, post_id, last_viewed_at, counted_day, counted_views }`, with a unique index on `(user_id, post_id)`: one row per user and post, however many views, so the collection grows with the posts a user saw, not with the views. Each view sets `last_viewed_at = max(stored, serverTimestamp)`, so an older view delivered late never moves an entry back. The same event also adds 1 to the viewed post's `stats.view_count` (one atomic server-side update that never touches the post's `version`), but a user counts **at most `VIEW_COUNT_DAILY_LIMIT_PER_USER` (5) views of the same post per day**. post-command-service only deduplicates views by `viewId`, so without the cap one user could raise a post's `viewCount` without limit. The day is the UTC calendar day of the event's `serverTimestamp`, never the consumer's clock, so a delayed or redelivered event is counted on the day the view happened. The count lives on the history row itself (`counted_day`, `YYYY-MM-DD` in UTC, and `counted_views`), updated in the same single-document write as `last_viewed_at` by an aggregation pipeline: a newer day restarts the count at 1, the same day under the limit adds 1, and a view over the limit, or from a day older than `counted_day` (a late delivery), leaves the count alone and is not counted, while it still moves `last_viewed_at` (the history tab still puts the post on top). The next day the user can add up to 5 views again. A redelivered event is skipped by its id, so it neither counts nor uses up one of the 5 views. History rows written before the cap have no `counted_day`: their next view starts the day at 1 (no backfill). A row removed by the TTL simply restarts the count. The new fields are not exposed by `GET /api/content/me/history`. A view of a post that is not projected is still recorded in the history but counted nowhere (logged as a warning, acked). Posts projected before view counting start at 0: past views are not backfilled. The history write, the counter, the idempotency check and its record run in one transaction. A TTL index on `last_viewed_at` removes entries not refreshed for `VIEW_HISTORY_RETENTION_DAYS` (90 by default); a changed value is applied in place at the next start.

### Collab join requests
Accepting and declining stay in post-command-service (`PUT /api/collabs/{collabId}/requests/{userId}/accept|decline`); this service projects the join request and member events and serves them.

- **Projection.** `rk.post.collab.request.created`, `.accepted`, `.declined`, `.deleted`, `rk.post.collab.member.left` and `.banned` (already bound to `q.post-query-service.post`) are projected into `collab_members { collab_id, user_id, status, role, requested_at, updated_at, version }`, one row per user and collab (unique `(collab_id, user_id)`). Each event carries the whole member row. The events have no version, so they are ordered by `occurredAt` (at millisecond precision): an event is applied only when it is newer than the row's `updated_at`, so an accept processed before its request is not undone by the request, and a late redelivery changes nothing. The write, the idempotency check and its record run in one transaction. `requested_at` is the member row's creation date (`createdAt` / `memberCreatedAt` of the event) and never changes.
- **In the feed.** A feed item, a `PostView` and a following feed item carry `viewer.collabRequestStatus` (the caller's row) and `collab.pendingRequestCount` (owner only, see "Feed API"). A page reads them with two queries whatever its size: the caller's rows for the page's collabs (one `$in`), and the pending counts of the collabs the caller owns (one aggregation, skipped when the caller owns none of them, reading index keys only).
- **The list.** `GET /api/content/collabs/{collabId}/requests` answers the collab's owner only (`post_collabs.owner_user_id`): anyone else gets 403 `not_collab_owner`, checked before any request is read; an unknown or deleted collab is 404 `collab_not_found`. `status` filters by membership status (`PENDING` by default, any `CollabMemberStatus`). Items are `{ user: UserSummary, status, requestedAt }`, oldest request first (`requestedAt`, then `userId`, ascending) so nobody waits forever; `user.pictureUrl` is signed. A requester no longer projected (deleted) is left out.
- Keyset pagination as for the grids: `limit` 1 to 50 (default 20), opaque `cursor`, `nextCursor` null on the last page. Every page is an index seek on `{ collab_id: 1, status: 1, requested_at: 1, user_id: 1 }`, which also serves the counts. A request whose status changes while the owner pages leaves that status's list; nobody is served twice.

### Collab join instructions
The ADMIN sets them in post-command-service (`PATCH /api/collabs/{collabId}/join-instructions`, or when opening the collab); this service projects them and decides who reads them.

- **Projection.** `CollabOpenedEvent.joinInstructions` (string or null; absent in events published before it existed) is stored on `post_collabs.join_instructions` (empty when none), with the event's `occurredAt` as `join_instructions_updated_at`. `rk.post.collab.join-instructions.updated` (`CollabJoinInstructionsUpdatedEvent { id, correlationId, occurredAt, collabId, joinInstructions }`, null when cleared), bound to `q.post-query-service.post`, replaces them when its `occurredAt` (millisecond precision) is newer than the stored one, with a version-gated write; an older or equal one changes nothing and is still recorded as processed. A duplicate is skipped by the idempotency record, a malformed payload goes straight to the dead-letter queue, and an update for a collab not projected yet is retried.
- **Who reads them.** The feed item, the `PostView` and the following feed item carry `collab.joinInstructions` only when the caller is the collab's owner (`post_collabs.owner_user_id`) or has an `ACCEPTED` row in `collab_members`; the ADMIN is an accepted member in post-command-service, and the creator's own row is covered by the owner rule. The check reuses the caller's membership lookup of "Collab join requests" (no extra query) and runs in the response mapping, so the profile grids and the requests list never carry the text.

## How the feed gets filled
The feed is prepared ahead of time over RabbitMQ, through the transactional outbox:

1. A trigger asks for a prefetch: a disconnect, a user silent for 3 minutes (a background sweeper on every replica), a newly registered user, or a claim that leaves less than one page unserved.
2. Skipped when the user already has the maximum of unserved items. Otherwise one transaction takes the user's lease and stores `UserFeedExhaustedEvent` for `x.feed.events` with routing key `rk.post.feed.exhausted`, carrying `reason`, `currentFeedSize` and `excludedPostIds`. Only one trigger per user can hold the lease.
3. recommendation-service answers on `rk.post.feed.recommended.sent`, consumed from `q.post-query-service.feed`. Its `correlationId` is the lease token.
4. The units are stored and the lease is released, only if that token still holds it. The lease also expires by itself, which covers a crashed instance or a lost response.

When a claim still finds nothing, the user is waiting, so it refills over gRPC, never RabbitMQ:

1. It takes the same lease. It first checks the stored feed again, because a build may have just finished.
2. It calls `GetRecommendedFeed` with reason `FEED_EMPTY`, the `excludedPostIds`, and the `x-correlation-id` metadata. The otelgrpc client handler opens a client span per attempt and sends `traceparent`/`tracestate` with it as the parent.
3. It stores the posts as feed units, continuing the position sequence, releases the lease and serves the first page in the same response. The lease is released whatever happens, also when the client went away, in which case the cancellation reaches MongoDB and the gRPC call.
4. If another trigger holds the lease it makes no call: it polls the stored feed for a short wait, and serves the page if units appear.

The low watermark runs after all this, so it cannot take the lease the refill needs, and it does not run when recommendation-service has nothing.

**gRPC client.** 4 attempts in total (3 retries) on `UNAVAILABLE`, `DEADLINE_EXCEEDED` and `RESOURCE_EXHAUSTED`, with an exponential backoff capped at 1 s and jitter, a limit per attempt and an overall limit, against a `dns:///` target with `round_robin` on recommendation-service's headless Service. Other codes fail at once. Each retry is logged.

**Where the timeouts come from.** Measured with the real image on CPU in WSL, **not in production**, with 20000 posts, 2000 users with history and their interactions:

| Call | p50 | Slowest |
| --- | --- | --- |
| New user | 0.13 s | 0.14 s |
| User with history | 4.4 s | 5.1 s |
| User with history, two runs at once | 6.0 s | 7.7 s |

A retry on a timeout runs the pipeline again, so the limit per attempt (10 s) is above the slowest run. The overall limit (20.5 s) fits two runs that time out and expires during the second wait, so a third run that could not finish never starts, while fast failures still get all 4 attempts. It stays below the 30 s of the HTTPRoute. A replica runs 2 pipelines at once and refuses the third with `RESOURCE_EXHAUSTED`: the retries wait less than a run takes, so that claim gets a 503 with `Retry-After`. Measure again under real load.

**Regenerating the stubs.** They are committed in `internal/feed/infrastructure/grpc/recommendationv1`, because the Dockerfile only copies `go.work`, `go-utils` and this service. Run `scripts/generate-grpc-stubs.sh` from `post-query-service/`. `protoc` must be on the `PATH`, or set in `PROTOC`, for example `PROTOC="python -m grpc_tools.protoc"`. The plugin versions are pinned in the script, not in `go.mod`, because `protoc-gen-go-grpc` requires a newer gRPC than the workspace uses.

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
| `FEED_FOLLOWING_MAX_FOLLOWS` | `2000` | The following feed reads the posts of at most this many of the caller's follows, the most recent ones, 1 to 5000. |
| `FEED_INSTANCE_ID` | hostname | Owner recorded on a lease. |
| `FEED_PREFETCH_MAX_UNSERVED` | `60` | Unserved items at which every trigger skips. Not below `FEED_DEFAULT_PAGE_SIZE`. |
| `FEED_PREFETCH_EXCLUDED_IDS_CAP` | `200` | Most ids sent as `excludedPostIds`. Not below `FEED_PREFETCH_MAX_UNSERVED`. |
| `FEED_PREFETCH_TIMEOUT_MS` | `5000` | Time limit of one prefetch request. |
| `FEED_LEASE_TTL_MS` | `300000` | When an unanswered asynchronous lease expires. Must exceed queue plus pipeline time. The pipeline takes seconds; queue time is an estimate. |
| `FEED_LEASE_RELEASE_TIMEOUT_MS` | `5000` | Time limit of a lease release. |
| `FEED_LOW_WATERMARK_CONCURRENCY` | `16` | Background low watermark checks at once. |
| `FEED_SWEEP_INTERVAL_MS` | `60000` | Pause between sweeps. |
| `FEED_SWEEP_STALE_AFTER_MS` | `180000` | How old a last heartbeat must be for the sweeper to act. |
| `FEED_SWEEP_BATCH_SIZE` | `100` | Sessions read per batch by a sweep. |
| `FEED_REFILL_LEASE_WAIT_MS` | `8000` | How long a claim waits for a build in flight. Just above the slowest measured run. |
| `FEED_REFILL_POLL_INTERVAL_MS` | `250` | Pause between reads during that wait. Not above the wait. |
| `FEED_REFILL_RETRY_AFTER_SECONDS` | `5` | `Retry-After` of the 503. |
| `FEED_REFILL_LEASE_TTL_MS` | `30000` | When the lease of a synchronous refill expires if its pod dies mid-call. Must exceed `RECOMMENDATION_GRPC_OVERALL_TIMEOUT_MS` and not exceed `FEED_LEASE_TTL_MS`. |
| `RECOMMENDATION_GRPC_ADDR` | required | gRPC target: `dns:///recommendation-service.recommendation-service-<env>.svc.cluster.local:50051`, or `dns:///recommendation-service:50051` in compose. |
| `RECOMMENDATION_GRPC_DIAL_TIMEOUT_MS` | `2000` | Time limit of one connection attempt. |
| `RECOMMENDATION_GRPC_PER_RETRY_TIMEOUT_MS` | `10000` | Time limit of one attempt. Must exceed the pipeline's duration. |
| `RECOMMENDATION_GRPC_OVERALL_TIMEOUT_MS` | `20500` | Time limit of the whole call. Not below the previous one. Expires during the second backoff after two timeouts, so no third run starts; fast failures still get every attempt. Keep it below `httpRoute.timeout`. |
| `RECOMMENDATION_GRPC_MAX_ATTEMPTS` | `4` | Total attempts, the first included. |
| `RECOMMENDATION_GRPC_BACKOFF_BASE_MS` | `250` | Wait before the first retry; it doubles each time. Not above the cap. |
| `RECOMMENDATION_GRPC_BACKOFF_MAX_MS` | `1000` | Longest wait between attempts. |
| `RECOMMENDATION_GRPC_BACKOFF_JITTER_PERCENT` | `20` | How much each wait varies, up or down. |
| `AZURE_STORAGE_ACCOUNT_URL` | required | Blob endpoint of the media account, the same value post-command-service builds the blob URLs from: `https://<account>.blob.core.windows.net`, or `http://<host>:10000/devstoreaccount1` for Azurite. |
| `AZURE_STORAGE_CONTAINER` | required | Media container (`post-media-dev` locally, `post-media` in prod). Only its blobs are signed. |
| `AZURE_STORAGE_ACCOUNT_KEY` | none | Account key, for Azurite only: refused unless the account URL is plain HTTP. Without it the service signs with a user delegation key, through Workload Identity (`AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_FEDERATED_TOKEN_FILE`, injected by the webhook). The identity needs Storage Blob Delegator on the account and Storage Blob Data Reader on the container. |
| `MEDIA_READ_SAS_WINDOW` | `24h` | Length of a signing window, a Go duration from `1m` to `72h`. A SAS lasts two windows. |
| `VIEW_HISTORY_RETENTION_DAYS` | `90` | How long a viewing history entry lives after the caller's last view of that post (TTL index), 1 to 3650. |
| `VIEW_COUNT_DAILY_LIMIT_PER_USER` | `5` | Most views of one post by one user counted on the post's `viewCount` per UTC day, at least 1. Further views of that day still update the viewing history. |

## Tests
`./test.sh` from `post-query-service/`. It needs Docker: integration tests use Testcontainers for MongoDB, RabbitMQ and Azurite (`mcr.microsoft.com/azure-storage/azurite:3.35.0`, where `GET /api/feed` and `GET /api/content/posts/{postId}` are checked to return media URLs that download). The gRPC tests run a real server on localhost. The comment list, profile grid, own tab, following feed and collab requests tests read the queries the service ran from the MongoDB profiler and explain them (one index scan, or for the following feed a merge of index scans, with no in-memory sort, and as many keys on a deep page as on page 2).
