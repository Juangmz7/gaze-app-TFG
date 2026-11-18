#!/usr/bin/env bash
# Starts the local k3s cluster again after stop.sh or `wsl --shutdown`. It
# creates nothing: if the cluster does not exist yet, run bootstrap.sh.
#
# It starts the databases of infra/local/compose.yaml (the cluster uses them),
# re-enables k3s' autostart with WSL and waits for the node. The pods come back
# on their own; Argo CD reconciles anything that drifted.
# Options:
#   --no-autostart   start k3s now but keep it from starting with WSL
#
# Run inside the Ubuntu WSL2 distro, from the repository root:
#   ./infra/k8s/bootstrap/start.sh [--no-autostart]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
# Host ports of the databases the cluster uses (same list as bootstrap.sh).
DB_PORTS=(5433 5434 5435 5436 7687 27017 10000)

ok()   { printf '\033[0;32m[OK]\033[0m    %s\n' "$1"; }
fail() { printf '\033[0;31m[FAIL]\033[0m  %s\n' "$1"; exit 1; }

usage() {
  sed -n '2,/^$/s/^# \{0,1\}//p' "${BASH_SOURCE[0]}"
  exit "${1:-0}"
}

no_autostart=false
for arg in "$@"; do
  case "$arg" in
    --no-autostart) no_autostart=true ;;
    -h|--help)      usage 0 ;;
    *)              printf 'unknown option: %s\n\n' "$arg" >&2; usage 1 ;;
  esac
done

# --- Prerequisites -----------------------------------------------------------
[ "$(uname -s)" = "Linux" ] || fail "run this inside the Ubuntu WSL2 distro (where k3s is installed)"
for bin in sudo ss docker kubectl; do
  command -v "$bin" >/dev/null 2>&1 || fail "$bin is not installed"
done
systemctl list-unit-files k3s.service 2>/dev/null | grep -q '^k3s\.service' \
  || fail "k3s is not installed: create the cluster with ./infra/k8s/bootstrap/bootstrap.sh"

# --- Databases ---------------------------------------------------------------
docker compose -f "${REPO_ROOT}/infra/local/compose.yaml" --env-file "${REPO_ROOT}/infra/local/.env" up -d >/dev/null
for port in "${DB_PORTS[@]}"; do
  if ! ss -Hltn "sport = :${port}" | awk '{print $4}' | grep -Eq "^(0\.0\.0\.0|\*|\[::\]):${port}$"; then
    fail "nothing listens on 0.0.0.0:${port}: set DB_BIND_IP=0.0.0.0 in infra/local/.env"
  fi
done
ok "databases running (${DB_PORTS[*]})"

# --- k3s ---------------------------------------------------------------------
if [ "$no_autostart" = true ]; then
  sudo systemctl start k3s
else
  sudo systemctl enable --now k3s >/dev/null 2>&1
fi
export KUBECONFIG="${KUBECONFIG:-${HOME}/.kube/config}"
for _ in $(seq 1 60); do
  kubectl get node gaze >/dev/null 2>&1 && break
  sleep 2
done
kubectl wait --for=condition=Ready node/gaze --timeout=180s >/dev/null \
  || fail "node gaze is not Ready after 3 minutes: check 'sudo journalctl -u k3s'"
if [ "$no_autostart" = true ]; then
  ok "k3s running (it will not start with WSL)"
else
  ok "k3s running"
fi

cat <<EOF

The pods take a few minutes to be Ready:  kubectl get pods -A
After a WSL restart, run again from an elevated PowerShell (Windows and LAN access):
  infra\\k8s\\bootstrap\\windows-portproxy.ps1 -Lan
EOF
