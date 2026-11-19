# Report - Application Layer

## What it does
Orchestrates reporting a user and the admin list of user reports.

## Key Design Choices
- **Validation before queries**: `UserReportService.reportUser` builds `ReportDetails` and runs `UserReport.assertValidClaim` (OTHER needs details, no self report) before reading anything.
- **Reportable users**: the reported user must exist and not be deleted or banned (`UserRepository.findPublicProfileData`), the same users `GET /profile/{userId}` answers 404 for; otherwise `ReportedUserNotFoundException` (404 `user_not_found`). A block between the two users does not prevent a report.
- **Idempotent per reporter and user while OPEN**: an existing OPEN report of the same reporter on the same user is returned (`created = false`, 200). Otherwise the report is inserted with `INSERT ... ON CONFLICT DO NOTHING`; losing the unique partial index to a concurrent request re-reads and returns the winner. `createdAt` is truncated to microseconds so the receipt matches the stored value.
- **Admin list (keyset)**: `listUserReports(status, cursor, limit)` validates `limit` then `cursor` (`PageLimit`, `KeysetCursor`: `invalid_limit` / `invalid_cursor`), fetches `limit + 1` rows and builds a `KeysetPage` ordered by `(createdAt, reportId)` DESC. Not cached; the reported user's current picture is signed in the controller.

## Components
- `UserReportService`
- `ReportUserCommand`
- `UserReportReceiptResponse`, `UserReportCreationResult`, `UserReportEntry`, `UserReportItemResponse` (`SignablePictureUrl`), `ReportedUserResponse`, `UserReportSnapshotResponse`, `UserReportListResponse`
- `UserReportRepository`
