# Local development / testing image.
# Build context is the repository root (see infra/local/app/compose.yaml):
# the module resolves common-packages/go-utils through go.work.
# GO_VERSION must match the go directive in go.work / the CI workflow.
ARG GO_VERSION=1.26.5

# Stage 1: Build
FROM golang:${GO_VERSION}-alpine AS builder
WORKDIR /src
COPY go.work go.work.sum ./
COPY common-packages/go-utils ./common-packages/go-utils
COPY post-query-service ./post-query-service
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/post-query-service ./post-query-service/cmd/api

# Stage 2: Run
FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=builder /out/post-query-service /post-query-service
ENTRYPOINT ["/post-query-service"]
