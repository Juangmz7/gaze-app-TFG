# Social Service

## Overview
This service handles social interactions and user profile management. It uses a **Domain-Driven Design (DDD)** approach with a strict **Hexagonal Architecture** (Domain, Application, Infrastructure layers).

## Domains
The service is divided into distinct bounded contexts (domains):
- **User**: Core user profile and identity management.
- **Follow**: Manages the directed follow graph between users.
- **Block**: Handles user blocking mechanisms to prevent unwanted interactions.
- **Post**: Listens to post-related events from other services.
- **Shared**: Common infrastructure, cross-cutting concerns, and outbox pattern implementation.

## Key Design Choices
1. **Clean Architecture**: Dependencies always point inwards. Infrastructure depends on Application; Application depends on Domain. The Domain has zero external dependencies (no Spring or DB annotations).
2. **Polyglot Persistence**: We use PostgreSQL as our primary source of truth (ACID guarantees), and Neo4j as a secondary graph database (for traversing social connections like friends-of-friends).
3. **Transactional Outbox Pattern**: To reliably publish events to RabbitMQ without distributed transactions, we save events to an `outbox` table in the *same transaction* as the domain changes. A relay then publishes them to RabbitMQ with publisher confirms (at-least-once; consumers deduplicate by message id). PostgreSQL is the source of truth and Neo4j is a projection fed by those events, so the outbox never spans two databases.
4. **Event-Driven**: We avoid synchronous HTTP calls between microservices where possible. State changes are communicated via RabbitMQ events.

Explore the `docs` folder inside each domain package for detailed layer-specific documentation.

## Profile by username
`GET /api/social/profile/by-username/{username}` answers `200` with exactly the body of `GET /api/social/profile/{userId}` (`UserProfileResponse`: `id`, `username`, `description`, `socialMedia`, `followerCount`, `followingCount`, `postCount`, `profilePic`, `following`, `followsMe`), for mentions (`@username`) and tagged users, which carry only a username. It resolves the user id and then runs the same use case output (`PublicUserProfileReader` cache + live relationship), and `profilePic` is read-signed like every other picture.
- The match is case-insensitive (`lower(username)`, served by the unique index `uk_users_username_lower` in `db/schema/social-service-prod.sql`; Keycloak usernames are unique and lowercase).
- The path value must be 1..64 characters of letters, digits, `_` and `.` (the Keycloak username charset, any letter case), else 400 `invalid_username`.
- Unknown, deleted, banned, and a block in either direction all answer the same 404 `NOT_FOUND`, so a lookup by name never tells them apart (`/profile/{userId}` answers 403 `BLOCKED` for a block). Your own username answers 400 `BAD_REQUEST`, like your own id (use `/profile/me`).

## Followers and following lists
`GET /api/social/follow/{userId}/followers` and `GET /api/social/follow/{userId}/following` answer `200 { items: { userId, username, profilePic: string | null, following: boolean }[], nextCursor: string | null }`, opened from the follower / following counters. `following` says whether the **caller** follows that user. Rows are the ACTIVE follows ordered by the follow's `created_at` DESC, then the listed user's id DESC.
- **Keyset pagination, never offset.** Query params `cursor` (optional, the opaque `nextCursor` of the previous page: base64url of the last row's `(created_at, user id)`) and `limit` (optional, 1..50, default 20). The query is `WHERE (created_at, user_id) < (cursor) ORDER BY created_at DESC, user_id DESC LIMIT limit+1` on an index that matches the sort exactly (`idx_follows_followers_page` on `(followed_id, created_at DESC, follower_id DESC)`, `idx_follows_following_page` on `(follower_id, created_at DESC, followed_id DESC)` in `db/schema/social-service-prod.sql`), so every page is an index seek; the extra row only tells whether `nextCursor` is set (`null` on the last page). `following` for the whole page is one `IN` query.
- Users in a block relation with the caller (either direction), banned or deleted accounts, and REMOVED / BLOCKED follows are left out. The list owner must be visible to the caller: unknown, deleted, banned or blocked either way answers the same 404 `NOT_FOUND` as `GET /profile/{userId}` for a missing user. Your own lists work.
- Errors: malformed cursor -> 400 `invalid_cursor`; bad limit (not a whole number from 1 to 50) -> 400 `invalid_limit`; malformed `userId` -> 400 `BAD_REQUEST` (every malformed path or query value of a typed parameter now answers 400 instead of 500).
- `profilePic` is read-signed like every other picture. The cursor helper (`KeysetCursor`, `PageLimit` in `shared/domain/pagination`, `KeysetPage` in `shared/application/pagination`) is shared for the other keyset-paged lists.

## Profile pictures (Azure Blob)
Profile pictures live in the private media container of post-command-service (`post-media-dev` locally, `post-media` in prod), under `avatars/{userId}/{uuid}.{jpg|png|webp}`.

**Uploading an avatar**
1. `POST /api/social/profile/me/picture/upload-url` with `{ contentType: 'image/jpeg' | 'image/png' | 'image/webp', sizeBytes: number }` (1..5242880). Answers `200 { uploadUrl, pictureUrl, expiresAt }`: `uploadUrl` is a write-only SAS (`sp=cw`, `sr=b`, 15 min) on a blob name the server generates; `pictureUrl` is the same blob URL without SAS; `expiresAt` is ISO-8601. Errors: 400 `unsupported_content_type` (also when missing), 400 `invalid_size` (missing, fractional or out of range), 404 unknown user.
2. The client `PUT`s the bytes to `uploadUrl` with `x-ms-blob-type: BlockBlob`.
3. The client sends `profilePicture: pictureUrl` in `PUT /api/social/profile/me` (also served on `/api/social/profile`). A URL of the media container is reduced to its bare blob URL (the read-signed URL the app got back from `GET` is accepted and stored bare) and, when it differs from the current picture, must be `avatars/{callerUserId}/{uuid}.{ext}` (else 400 `invalid_profile_picture`) and exist in Blob Storage (`getProperties`, else 400 `profile_picture_not_uploaded`). URLs of other hosts are stored as before. The blob check runs outside any database transaction.

**Reading pictures.** Every `profilePic` / `profilePicture` returned (`/profile/me`, `/profile/{userId}`, `/profile/by-username/{username}`, `PUT /profile/me`, `/recommended/users`, `/block/users`, `/follow/{userId}/followers`, `/follow/{userId}/following`) carries a read-only SAS, applied in one place (`PictureUrlResponseSigner`) on the way out, after the Redis-cached result: a signed URL is never stored or cached. Same rules as post-query-service: `sp=r`, `sr=b`, HTTPS only unless the account is plain-HTTP Azurite, only URLs of the configured account and container (others, and null, unchanged); fixed windows of `azure.storage.read-sas-window` (24 h) from the epoch, `st` = window start - 5 min, `se` = window start + 2 windows, so a URL is byte-identical for a whole window. Signing is a local HMAC: Azurite signs with its account key; prod fetches one user delegation key per window through Workload Identity (`DefaultAzureCredential`), keeps the previous one as a fallback, and retries a failed fetch at most every 30 s.

| Property (env) | Default | Meaning |
|---|---|---|
| `azure.storage.account-url` (`AZURE_STORAGE_ACCOUNT_URL`) | required | Blob endpoint, the same as post-command-service. |
| `azure.storage.container` (`AZURE_STORAGE_CONTAINER`) | required | Media container. |
| `azure.storage.upload-sas-ttl` (`AZURE_STORAGE_UPLOAD_SAS_TTL`) | `PT15M` | Upload SAS lifetime (not longer than the read window). |
| `azure.storage.read-sas-window` (`MEDIA_READ_SAS_WINDOW`) | `24h` | Signing window, 1 min to 72 h. |
| `azure.storage.use-account-key` / `account-key` | `false` / none | Azurite only (http account URL). |

The prod identity needs Storage Blob Delegator on the account and Storage Blob Data Contributor on the container. Tests run a real Azurite (`mcr.microsoft.com/azure-storage/azurite:3.35.0`) for every Spring context (`TestcontainersConfiguration`).
