# Report - Content reports (task 55)

## What it does
Users report a post (`POST /api/posts/{postId}/reports`) or a comment
(`POST /api/posts/{postId}/comments/{commentId}/reports`) with a preset reason or their own
text; ADMIN users (Keycloak realm role `ADMIN`) list the reports
(`GET /api/posts/reports`, `GET /api/posts/comments/reports`). User reports live in
social-service.

## Key Design Choices
- **Snapshot as evidence**: a report stores what the target looked like at report time in a
  `jsonb` `snapshot` (post: description, bare media urls, author id; comment: content, author
  id, post id), so edits or deletions do not erase the evidence.
- **Domain rules**: `ReportDetails` trims and caps the text at 1000 characters; `ReportClaim`
  requires details for `OTHER`; `PostReport.open` / `CommentReport.open` reject reporting your
  own content (`cannot_report_own_content`). Targets must be an `ACCEPTED` post / `ACTIVE`
  comment on an `ACCEPTED` post, otherwise `post_not_found` / `comment_not_found` (404).
- **Idempotency without races**: one OPEN report per reporter and target, enforced by the
  partial unique indexes `uk_post_reports_open_reporter_post` /
  `uk_comment_reports_open_reporter_comment` (`WHERE status = 'OPEN'`). The use case returns an
  existing OPEN report (200) and otherwise inserts with `INSERT ... ON CONFLICT DO NOTHING`
  (201); a lost race re-reads the winner's row and answers 200.
- **Keyset lists**: `status` (default `OPEN`), `cursor`, `limit` (1..50, default 20); rows are
  read with `WHERE status = ? AND (created_at, id) < (?, ?) ORDER BY created_at DESC, id DESC
  LIMIT limit+1` on the `(status, created_at DESC, id DESC)` index. `nextCursor` is null on the
  last page.

## Components
- Domain: `ReportReason`, `ReportStatus`, `ReportDetails`, `ReportClaim`, `PostReport`,
  `CommentReport`, `PostReportSnapshot`, `CommentReportSnapshot`, exceptions.
- Application: `ReportPostUseCase`, `ReportCommentUseCase`, `ListPostReportsUseCase`,
  `ListCommentReportsUseCase`, `PostReportRepository`, `CommentReportRepository`,
  `ReportResponseMapper`.
- Infrastructure: `ReportController`, `PostReportEntity`, `CommentReportEntity`,
  `*JpaRepository` (native insert and keyset queries), `*RepositoryImpl`, `ReportMapper`.
