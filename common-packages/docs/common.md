# Common Package

## What it does
The `common` package provides generic utility functions and standardized HTTP response handlers that are shared across all microservices. It includes the `RespondWithError` function, which consistently formats and sends API error responses using a standard JSON structure (`ApiErrorResponse`). This standard response includes timestamps, HTTP status codes, specific application codes, messages, and a `TraceID` (which is extracted from headers or auto-generated if missing). Furthermore, it provides utility functions such as `ParseAnyToBytes` for safely converting arbitrary Go types into byte slices via direct casting or JSON serialization.

## Packages used
- `encoding/json`
- `fmt`
- `net/http`
- `time`
- `github.com/google/uuid`

## Why
Standardizing API error responses is vital for a consistent consumer experience, making client-side error handling predictable and structured. The inclusion of `TraceID` is crucial for distributed tracing across the microservices ecosystem, allowing developers to track requests through the system. Providing utility functions like `ParseAnyToBytes` eliminates repetitive boilerplate code, centralizing serialization logic that can be reused reliably by other packages.
