#!/usr/bin/env bash
# test.sh — Run all tests for the application

set -u
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
NC='\033[0m'

ok()    { printf "${GREEN}[OK]${NC}    %s\n" "$1"; }
warn()  { printf "${YELLOW}[WARN]${NC}  %s\n" "$1"; }
fail()  { printf "${RED}[FAIL]${NC}  %s\n" "$1"; }

ENV=${1:-test}
EXIT_CODE=0

echo "── Running tests (env: $ENV) ───────────────────────"

if [ -f "go.mod" ]; then
  if go test -v ./...; then
    ok "All tests pass"
  else
    fail "Tests failed"
    EXIT_CODE=1
  fi
else
  warn "go.mod not found — skipping tests"
  EXIT_CODE=1
fi

echo ""
echo "── Summary ─────────────────────────────────────────"

if [ $EXIT_CODE -eq 0 ]; then
  ok "All tests completed successfully."
else
  fail "Some tests failed. Please review the output above."
fi

exit $EXIT_CODE
