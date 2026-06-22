#!/usr/bin/env bash
# init.sh — Environment verification and initialization
#
# The agent runs this script at the START of a session and before
# declaring any task as `done`. If it fails, the session must not proceed.
#
# Expected output: clear exit codes and blocks marked with [OK]/[FAIL].

set -u
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
NC='\033[0m'

ok()    { printf "${GREEN}[OK]${NC}    %s\n" "$1"; }
warn()  { printf "${YELLOW}[WARN]${NC}  %s\n" "$1"; }
fail()  { printf "${RED}[FAIL]${NC}  %s\n" "$1"; }

EXIT_CODE=0
ENV=${1:-test}

echo "── 1. Checking environment ────────────────────────────"

# Maven available
if ! command -v ./mvnw >/dev/null 2>&1; then
  fail "./mvnw is not installed"
  exit 1
fi
ok "./mvnw -> $(./mvnw --version | head -1)"

# Java available
if ! command -v java >/dev/null 2>&1; then
  fail "java is not installed"
  exit 1
fi
ok "java -> $(java -version 2>&1 | head -1)"



echo ""
echo "── 3. Running tests ───────────────────────────────────"

if ./test.sh "$ENV"; then
  ok "Tests execution via test.sh passed"
else
  fail "Tests execution via test.sh failed"
  EXIT_CODE=1
fi

echo ""
echo "── 4. Summary ─────────────────────────────────────────"

if [ $EXIT_CODE -eq 0 ]; then
  ok "Environment ready. You can start working."
else
  fail "Environment NOT ready. Fix the errors before proceeding."
fi

exit $EXIT_CODE