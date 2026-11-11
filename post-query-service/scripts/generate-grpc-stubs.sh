#!/usr/bin/env bash
# Regenerates the recommendation-service stubs from proto/recommendation/v1, the
# contract owned by recommendation-service. They are committed here because the
# Dockerfile only copies go.work, go-utils and post-query-service.
#
# The plugins are not go.mod tools on purpose: protoc-gen-go-grpc requires a newer
# google.golang.org/grpc than the workspace pins, and a tool would raise it.
#
# PROTOC is the compiler command, for example
#   PROTOC="python -m grpc_tools.protoc" scripts/generate-grpc-stubs.sh
set -euo pipefail

PROTOC_GEN_GO_VERSION=v1.36.11
PROTOC_GEN_GO_GRPC_VERSION=v1.6.2
MODULE=github.com/Juangmz7/gaze-app-TFG/post-query-service

service_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
plugins="$(mktemp -d)"
trap 'rm -rf "$plugins"' EXIT

# Outside the workspace, so installing them changes neither go.mod nor go.work.
(
  cd "$plugins"
  GOWORK=off GOBIN="$plugins" go install "google.golang.org/protobuf/cmd/protoc-gen-go@$PROTOC_GEN_GO_VERSION"
  GOWORK=off GOBIN="$plugins" go install "google.golang.org/grpc/cmd/protoc-gen-go-grpc@$PROTOC_GEN_GO_GRPC_VERSION"
)

${PROTOC:-protoc} \
  --plugin=protoc-gen-go="$plugins/protoc-gen-go" \
  --plugin=protoc-gen-go-grpc="$plugins/protoc-gen-go-grpc" \
  -I "$service_root/../proto" \
  --go_out="$service_root" --go_opt="module=$MODULE" \
  --go-grpc_out="$service_root" --go-grpc_opt="module=$MODULE" \
  recommendation/v1/recommendation.proto
