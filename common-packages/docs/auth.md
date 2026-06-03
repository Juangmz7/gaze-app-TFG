# Auth Package

## What it does
The `auth` package provides functionality to authenticate incoming HTTP requests using JSON Web Tokens (JWT). It automatically fetches and caches JSON Web Key Sets (JWKS) from a remote URL to verify token signatures. The package includes an `Authenticate` middleware that extracts the bearer token from the `Authorization` header, validates its signature, issuer, and audience, and then injects the parsed token claims into the request context for downstream handlers to consume. It also provides an `AuthConfig` struct and `LoadAuth` function to load necessary configuration from environment variables.

## Packages used
- `context`
- `fmt`
- `log/slog`
- `net/http`
- `os`
- `strings`
- `time`
- `github.com/lestrrat-go/jwx/v2/jwk`
- `github.com/lestrrat-go/jwx/v2/jwt`
- `github.com/Juangmz7/TFG/common-packages/go-utils/common`

## Why
Implementing a JWKS cache ensures that the system can handle dynamic key rotation by the identity provider without requiring service restarts. Caching these keys and refreshing them in the background ensures minimal latency for authentication during user requests. By encapsulating JWT validation and configuration into a reusable middleware, we guarantee a consistent, unified security approach across all microservices and significantly reduce code duplication.
