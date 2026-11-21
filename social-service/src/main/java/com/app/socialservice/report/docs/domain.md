# Report - Domain Layer

## What it does
Defines a user report (App Store guideline 1.2): a user reports another user's profile with a preset reason or their own text, and ADMIN users review the reports.

## Key Design Choices
- **Same model as the content reports of post-command-service (task 55)**: reason enum, trimmed `details`, `OPEN` / `RESOLVED` / `DISMISSED` status, snapshot of the target at report time.
- **Invariants at construction**: `ReportDetails` trims the text, treats a blank one as none and rejects more than 1000 characters. `UserReport.open` requires details for `OTHER` and rejects a self report (`CannotReportSelfException`). `assertValidClaim` runs the same checks before any query.
- **Snapshot, not just an id**: `UserReportSnapshot` keeps username, description, socialMedia and the bare profile picture URL as they were when the report was made, so the evidence survives later edits or deletion.

## Components
### Aggregate Root
- `UserReport`
### Value Objects
- `ReportDetails`, `UserReportSnapshot`
### Enums
- `UserReportReason` (SPAM, HARASSMENT, HATE_SPEECH, IMPERSONATION, INAPPROPRIATE_PROFILE, UNDERAGE, OTHER), `ReportStatus`
### Exceptions
- `InvalidReportDetailsException`, `CannotReportSelfException`, `ReportedUserNotFoundException`
