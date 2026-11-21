#!/usr/bin/env bash
# Stops the local k3s cluster WITHOUT deleting it: every pod and k3s process
# stops (and the Argo CD / Grafana port-forwards of start.sh), and nothing is removed (cluster state, PersistentVolumes, images and
# the gaze-local-ca stay, so devices keep trusting the CA). Start it again
# with start.sh. To delete the cluster instead, use teardown.sh.
#
# By default k3s starts again on its own with WSL (its systemd unit stays
# enabled). Options:
#   --no-autostart   also disable the unit: k3s stays off until start.sh
#   --with-dbs       also stop the databases of infra/local/compose.yaml
#
# Run inside the Ubuntu WSL2 distro, from the repository root:
#   ./infra/k8s/bootstrap/stop.sh [--no-autostart] [--with-dbs]
# `wsl --shutdown` also stops the cluster (nothing is lost), but k3s comes
# back with the next WSL start unless --no-autostart was used once.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
KILLALL="/usr/local/bin/k3s-killall.sh"

ok()   { printf '\033[0;32m[OK]\033[0m    %s\n' "$1"; }
warn() { printf '\033[0;33m[WARN]\033[0m  %s\n' "$1"; }
fail() { printf '\033[0;31m[FAIL]\033[0m  %s\n' "$1"; exit 1; }

usage() {
  sed -n '2,/^$/s/^# \{0,1\}//p' "${BASH_SOURCE[0]}"
  exit "${1:-0}"
}

no_autostart=false
with_dbs=false
for arg in "$@"; do
  case "$arg" in
    --no-autostart) no_autostart=true ;;
    --with-dbs)     with_dbs=true ;;
    -h|--help)      usage 0 ;;
    *)              printf 'unknown option: %s\n\n' "$arg" >&2; usage 1 ;;
  esac
done

# --- Prerequisites -----------------------------------------------------------
[ "$(uname -s)" = "Linux" ] || fail "run this inside the Ubuntu WSL2 distro (where k3s is installed)"
command -v sudo >/dev/null 2>&1 || fail "sudo is not installed"
systemctl list-unit-files k3s.service 2>/dev/null | grep -q '^k3s\.service' \
  || fail "k3s is not installed: create the cluster with ./infra/k8s/bootstrap/bootstrap.sh"

# --- Port-forwards started by start.sh ----------------------------------------
PF_DIR="${XDG_STATE_HOME:-${HOME}/.local/state}/gaze/port-forwards"
for pid_file in "$PF_DIR"/*.pid; do
  [ -f "$pid_file" ] || continue
  kill -- "-$(cat "$pid_file")" 2>/dev/null || true
  rm -f "$pid_file"
done

# --- k3s ---------------------------------------------------------------------
sudo systemctl stop k3s
# Stopping the unit leaves the pods running (containerd shims): killall stops
# them, unmounts their volumes and removes CNI interfaces and iptables rules.
# It deletes no data: /var/lib/rancher/k3s and /etc/rancher/k3s stay.
if [ -x "$KILLALL" ]; then
  sudo "$KILLALL" >/dev/null
else
  warn "${KILLALL} is missing: pods may keep running until 'wsl --shutdown'"
fi
ok "k3s stopped (cluster state kept)"

if [ "$no_autostart" = true ]; then
  sudo systemctl disable k3s >/dev/null 2>&1
  ok "k3s will not start with WSL until ./infra/k8s/bootstrap/start.sh"
fi

# --- Databases (optional) ----------------------------------------------------
if [ "$with_dbs" = true ]; then
  # Ubuntu's docker.io ships without the compose plugin (see start.sh).
  if docker compose version >/dev/null 2>&1; then
    compose=(docker compose)
  elif command -v docker-compose >/dev/null 2>&1; then
    compose=(docker-compose)
  else
    fail "docker compose is not installed: the databases were not stopped (stop them from Docker Desktop, or 'sudo apt install docker-compose-v2')"
  fi
  "${compose[@]}" -f "${REPO_ROOT}/infra/local/compose.yaml" --env-file "${REPO_ROOT}/infra/local/.env" stop
  ok "databases stopped (data kept)"
fi

echo
echo "Start it again with: ./infra/k8s/bootstrap/start.sh"
