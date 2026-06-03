# Server Package

## What it does
The `server` package provides utilities for configuring and starting the HTTP server for the microservices. It encapsulates the boilerplate required for starting an `http.Server` and ensures graceful shutdowns when an interrupt signal is received, preventing active connections from being abruptly dropped. It separates the server initialization and the routing mechanisms, allowing routes to be injected from the outside without coupling the server infrastructure to specific HTTP frameworks or middleware setups.

## Packages used
- `context`
- `fmt`
- `log`
- `net/http`
- `time`

## Why
By decoupling the `http.Server` initialization from the router configuration, we make the codebase more modular and easier to test. The `initialization.go` file provides a single `StartServer` function that focuses exclusively on server lifecycle management (starting, listening, and graceful termination). This avoids clustering server lifecycle logic within `main.go`, and leaves the HTTP handler configurations entirely up to the caller, meaning you can easily swap out routing layers or mock handlers for tests without rewriting server lifecycle code.
