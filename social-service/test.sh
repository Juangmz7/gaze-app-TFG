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

EXIT_CODE=0

echo "── Running tests ───────────────────────────────────"

if [ -f "pom.xml" ]; then
  # We do not redirect 2>&1 to /dev/null or anything here to allow test output to be visible
  if ./mvnw test -Dspring.profiles.active=test --no-transfer-progress; then
    ok "All tests pass"
  else
    fail "Tests failed"
    EXIT_CODE=1
  fi
else
  warn "pom.xml not found — skipping tests"
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
