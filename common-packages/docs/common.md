# Common Package

## What it does
The `common` package serves as a collection of shared utilities and standard structures used across all microservices. It currently provides two main functionalities:

1. **Standardized Error Responses (`error_response.go`)**: 
   Provides a structured format (`ApiErrorResponse`) and a helper function (`RespondWithError`) for returning consistent JSON error payloads to API clients. These responses include HTTP status, application-specific error codes, human-readable messages, timestamps, request paths, correlation trace IDs, and optional field-level validation details (`ApiErrorDetail`).

2. **Type Parsing Utilities (`utils.go`)**:
   Provides a utility function `ParseAnyToBytes` that safely converts an unknown `any` type variable into a byte slice (`[]byte`). It explicitly handles strings and byte slices for performance, while gracefully falling back to JSON serialization for complex types (like structs or maps).

## Packages used
- `encoding/json`: Used for marshaling arbitrary data types into byte slices and encoding error responses as JSON.
- `net/http`: Used for interacting with the HTTP ResponseWriter and Request to build the error responses.
- `time`: Used to stamp error responses with the exact time of failure (`time.Now().UTC()`).
- `fmt`: Used for formatting errors in the parsing utility.
- `github.com/google/uuid`: Used to generate a unique `TraceID` when one is not provided in the request headers (`X-Trace-Id`), ensuring every error can be uniquely tracked.

## Why
Having a shared `common` package prevents duplication of boilerplate code across multiple services and maintains architectural consistency:
- **Standardized Errors:** When every microservice responds with the exact same error schema, client applications (like frontend SPAs or mobile apps) can implement a single error-handling mechanism. The inclusion of `TraceID` is crucial for distributed debugging, allowing developers to correlate user-facing errors with internal logs across different microservices.
- **Robust Type Conversion:** In Go, working with generic `any` interfaces (especially when dealing with messaging queues like RabbitMQ or generic data structures) often requires safely converting payloads to byte slices. Providing a dedicated `ParseAnyToBytes` function centralizes this logic, avoiding panics and ensuring that complex data structures are properly serialized into JSON before transmission.
