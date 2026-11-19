# Report - Infrastructure Layer

## What it does
Exposes the user report endpoints and stores the reports in PostgreSQL.

## Key Design Choices
- **Endpoints** (`UserReportController`, `/api/social/reports/users`):
  - `POST` with `ReportUserRequest { reportedUserId: uuid, reason, details: string | null }` answers 201 `{ reportId, status, createdAt }`, or 200 with the reporter's existing OPEN report on that user. A malformed UUID, an unknown reason or malformed JSON fails body parsing and answers 400 `VALIDATION_ERROR`, never 500.
  - `GET ?status=OPEN&cursor=&limit=` answers `{ items: { reportId, reporterUserId, reportedUser: { userId, username, profilePic }, reason, details, snapshot, status, createdAt }[], nextCursor }`. It requires the realm role ADMIN (`SecurityConfig`, 403 `FORBIDDEN` otherwise). `reportedUser` is the user's current data, with `profilePic` read-signed by `PictureUrlResponseSigner`; `snapshot` is returned as stored (bare picture URL).
- **Table `user_reports`** (`db/schema/social-service-prod.sql`, `UserReportEntity` for create-drop tests): snapshot as `jsonb`, status `OPEN` by default with a check constraint, `idx_user_reports_status_created_at_id (status, created_at DESC, id DESC)` for the keyset list and the unique partial index `uk_user_reports_open_reporter_reported (reporter_user_id, reported_user_id) WHERE status = 'OPEN'`, which makes the native insert idempotent and serves the OPEN lookup. Partial indexes cannot be declared on the entity, so create-drop test databases rely on the lookup before the insert.
- **Queries** (`JpaUserReportRepository`, native, SQL as interface constants so the schema test can EXPLAIN them): the page condition is `(created_at, id) < (:afterCreatedAt, :afterReportId)` with `ORDER BY created_at DESC, id DESC LIMIT :fetchSize`, and the first page has its own query. The reported user's current username and picture come from a primary key lookup on `users` (left join, with the snapshot as a fallback).

## Components
- `UserReportController`, `ReportUserRequest`
- `UserReportEntity`, `UserReportSnapshotPayload`
- `JpaUserReportRepository`, `UserReportRepositoryImpl`
