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

## Profile pictures (Azure Blob)
Profile pictures live in the private media container of post-command-service (`post-media-dev` locally, `post-media` in prod), under `avatars/{userId}/{uuid}.{jpg|png|webp}`.

**Uploading an avatar**
1. `POST /api/social/profile/me/picture/upload-url` with `{ contentType: 'image/jpeg' | 'image/png' | 'image/webp', sizeBytes: number }` (1..5242880). Answers `200 { uploadUrl, pictureUrl, expiresAt }`: `uploadUrl` is a write-only SAS (`sp=cw`, `sr=b`, 15 min) on a blob name the server generates; `pictureUrl` is the same blob URL without SAS; `expiresAt` is ISO-8601. Errors: 400 `unsupported_content_type` (also when missing), 400 `invalid_size` (missing, fractional or out of range), 404 unknown user.
2. The client `PUT`s the bytes to `uploadUrl` with `x-ms-blob-type: BlockBlob`.
3. The client sends `profilePicture: pictureUrl` in `PUT /api/social/profile/me` (also served on `/api/social/profile`). A URL of the media container is reduced to its bare blob URL (the read-signed URL the app got back from `GET` is accepted and stored bare) and, when it differs from the current picture, must be `avatars/{callerUserId}/{uuid}.{ext}` (else 400 `invalid_profile_picture`) and exist in Blob Storage (`getProperties`, else 400 `profile_picture_not_uploaded`). URLs of other hosts are stored as before. The blob check runs outside any database transaction.

**Reading pictures.** Every `profilePic` / `profilePicture` returned (`/profile/me`, `/profile/{userId}`, `PUT /profile/me`, `/recommended/users`, `/block/users`) carries a read-only SAS, applied in one place (`PictureUrlResponseSigner`) on the way out, after the Redis-cached result: a signed URL is never stored or cached. Same rules as post-query-service: `sp=r`, `sr=b`, HTTPS only unless the account is plain-HTTP Azurite, only URLs of the configured account and container (others, and null, unchanged); fixed windows of `azure.storage.read-sas-window` (24 h) from the epoch, `st` = window start - 5 min, `se` = window start + 2 windows, so a URL is byte-identical for a whole window. Signing is a local HMAC: Azurite signs with its account key; prod fetches one user delegation key per window through Workload Identity (`DefaultAzureCredential`), keeps the previous one as a fallback, and retries a failed fetch at most every 30 s.

| Property (env) | Default | Meaning |
|---|---|---|
| `azure.storage.account-url` (`AZURE_STORAGE_ACCOUNT_URL`) | required | Blob endpoint, the same as post-command-service. |
| `azure.storage.container` (`AZURE_STORAGE_CONTAINER`) | required | Media container. |
| `azure.storage.upload-sas-ttl` (`AZURE_STORAGE_UPLOAD_SAS_TTL`) | `PT15M` | Upload SAS lifetime (not longer than the read window). |
| `azure.storage.read-sas-window` (`MEDIA_READ_SAS_WINDOW`) | `24h` | Signing window, 1 min to 72 h. |
| `azure.storage.use-account-key` / `account-key` | `false` / none | Azurite only (http account URL). |

The prod identity needs Storage Blob Delegator on the account and Storage Blob Data Contributor on the container. Tests run a real Azurite (`mcr.microsoft.com/azure-storage/azurite:3.35.0`) for every Spring context (`TestcontainersConfiguration`).
