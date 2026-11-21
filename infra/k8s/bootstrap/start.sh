#!/usr/bin/env bash
# Starts the local k3s cluster again after stop.sh or `wsl --shutdown`. It
# creates nothing: if the cluster does not exist yet, run bootstrap.sh.
#
# It starts the databases of infra/local/compose.yaml (the cluster uses them),
# re-enables k3s' autostart with WSL and waits for the node. The pods come back
# on their own; Argo CD reconciles anything that drifted.
#
# Then it leaves two port-forwards running in the background (WSL forwards its
# localhost, so they open from the Windows browser too):
#   Argo CD   https://localhost:8080   (ARGOCD_PORT to change it)
#   Grafana   http://localhost:3000    (GRAFANA_PORT to change it)
# They reconnect on their own when the pod restarts; stop.sh ends them.
# Options:
#   --no-autostart     start k3s now but keep it from starting with WSL
#   --no-port-forward  do not start the port-forwards
#
# Run inside the Ubuntu WSL2 distro, from the repository root:
#   ./infra/k8s/bootstrap/start.sh [--no-autostart] [--no-port-forward]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
# Host ports of the databases the cluster uses (same list as bootstrap.sh).
DB_PORTS=(5433 5434 5435 5436 7687 27017 10000)

ok()   { printf '\033[0;32m[OK]\033[0m    %s\n' "$1"; }
warn() { printf '\033[0;33m[WARN]\033[0m  %s\n' "$1"; }
fail() { printf '\033[0;31m[FAIL]\033[0m  %s\n' "$1"; exit 1; }

usage() {
  sed -n '2,/^$/s/^# \{0,1\}//p' "${BASH_SOURCE[0]}"
  exit "${1:-0}"
}

no_autostart=false
port_forward=true
for arg in "$@"; do
  case "$arg" in
    --no-autostart)    no_autostart=true ;;
    --no-port-forward) port_forward=false ;;
    -h|--help)      usage 0 ;;
    *)              printf 'unknown option: %s\n\n' "$arg" >&2; usage 1 ;;
  esac
done

# --- Prerequisites -----------------------------------------------------------
[ "$(uname -s)" = "Linux" ] || fail "run this inside the Ubuntu WSL2 distro (where k3s is installed)"
for bin in sudo ss kubectl; do
  command -v "$bin" >/dev/null 2>&1 || fail "$bin is not installed"
done
systemctl list-unit-files k3s.service 2>/dev/null | grep -q '^k3s\.service' \
  || fail "k3s is not installed: create the cluster with ./infra/k8s/bootstrap/bootstrap.sh"

# --- Databases ---------------------------------------------------------------
db_ports_missing() {  # prints the DB ports nothing listens on for every interface
  local port
  for port in "${DB_PORTS[@]}"; do
    ss -Hltn "sport = :${port}" | awk '{print $4}' | grep -Eq "^(0\.0\.0\.0|\*|\[::\]):${port}$" \
      || printf '%s ' "$port"
  done
}

# They may already run elsewhere (Docker Desktop starts the compose stack on its
# own, restart: unless-stopped): then compose is not needed, and running it
# against this distro's own Docker Engine would start a second copy competing
# for the same ports.
if [ -n "$(db_ports_missing)" ]; then
  # Ubuntu's docker.io ships without the compose plugin; Docker Desktop's CLI and
  # docker-compose-v2 have it.
  if docker compose version >/dev/null 2>&1; then
    compose=(docker compose)
  elif command -v docker-compose >/dev/null 2>&1; then
    compose=(docker-compose)
  else
    fail "databases not running on ports $(db_ports_missing)and docker compose is not installed: start Docker Desktop, or 'sudo apt install docker-compose-v2'"
  fi
  "${compose[@]}" -f "${REPO_ROOT}/infra/local/compose.yaml" --env-file "${REPO_ROOT}/infra/local/.env" up -d >/dev/null
  missing="$(db_ports_missing)"
  [ -z "$missing" ] || fail "nothing listens on 0.0.0.0 for ports ${missing}: set DB_BIND_IP=0.0.0.0 in infra/local/.env"
fi
ok "databases running (${DB_PORTS[*]})"

# --- k3s ---------------------------------------------------------------------
# sudo only when something changes, so a second run needs no password.
if [ "$no_autostart" = true ]; then
  systemctl is-active --quiet k3s || sudo systemctl start k3s
elif ! systemctl is-active --quiet k3s || ! systemctl is-enabled --quiet k3s; then
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

# --- Port-forwards -----------------------------------------------------------
# Each forward is a loop in its own session: `kubectl port-forward` exits when
# its pod restarts (or is not Ready yet, right after boot) and the loop starts
# it again. The pid file holds the session leader, so stop.sh can end the loop
# and its kubectl together.
PF_DIR="${XDG_STATE_HOME:-${HOME}/.local/state}/gaze/port-forwards"
ARGOCD_PORT="${ARGOCD_PORT:-8080}"
GRAFANA_PORT="${GRAFANA_PORT:-3000}"

start_port_forward() {  # name namespace service local-port:service-port
  local name="$1" ns="$2" svc="$3" ports="$4" pid_file="${PF_DIR}/$1.pid"
  if [ -f "$pid_file" ]; then
    kill -- "-$(cat "$pid_file")" 2>/dev/null || true
    rm -f "$pid_file"
  fi
  if ss -Hltn "sport = :${ports%%:*}" | grep -q .; then
    warn "port ${ports%%:*} is in use: ${name} is not forwarded (set $(echo "$name" | tr '[:lower:]' '[:upper:]')_PORT)"
    return
  fi
  setsid bash -c "while true; do
      kubectl -n '${ns}' port-forward 'svc/${svc}' '${ports}' --address 127.0.0.1
      sleep 3
    done" >"${PF_DIR}/${name}.log" 2>&1 </dev/null &
  echo $! >"$pid_file"
}

if [ "$port_forward" = true ]; then
  mkdir -p "$PF_DIR"
  start_port_forward argocd  argocd        argocd-server "${ARGOCD_PORT}:443"
  start_port_forward grafana observability lgtm          "${GRAFANA_PORT}:3000"
  ok "port-forwards started (logs in ${PF_DIR})"
fi

cat <<EOF

The pods take a few minutes to be Ready:  kubectl get pods -A
  Argo CD:  https://localhost:${ARGOCD_PORT}  (user admin, password below)
            kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d
  Grafana:  http://localhost:${GRAFANA_PORT}
  (both answer once their pod is Ready; with --no-port-forward they are not started)
After a WSL restart, run again from an elevated PowerShell (Windows and LAN access):
  infra\\k8s\\bootstrap\\windows-portproxy.ps1 -Lan
EOF
