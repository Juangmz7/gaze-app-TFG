#!/usr/bin/env bash
# Installs the local single-node k3s cluster (WSL2 Ubuntu) and hands it over
# to Argo CD.
#
# Prerequisites (inside the Ubuntu WSL2 distro):
#   - systemd enabled: /etc/wsl.conf -> [boot] systemd=true, then `wsl --shutdown`
#   - Docker Engine installed in Ubuntu (not Docker Desktop's WSL integration),
#     running the databases of infra/local/compose.yaml with DB_BIND_IP=0.0.0.0
#   - helm, curl, sudo
# Run from the repository root:  ./infra/k8s/bootstrap/bootstrap.sh
#
# After this, Argo CD syncs everything else from git (branch main):
# platform controllers -> per-environment infra -> microservices.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
K3S_VERSION="v1.35.2+k3s1"
ARGOCD_CHART_VERSION="10.11.6"
# Host ports of the databases the cluster uses (infra/local/.env defaults),
# see infra/k8s/platform/external-databases.
DB_PORTS=(5433 5434 5435 5436 7687 27017 10000)

ok()   { printf '\033[0;32m[OK]\033[0m    %s\n' "$1"; }
fail() { printf '\033[0;31m[FAIL]\033[0m  %s\n' "$1"; exit 1; }

# --- Prerequisites -----------------------------------------------------------
[ "$(uname -s)" = "Linux" ] || fail "run this inside the Ubuntu WSL2 distro (k3s only runs on Linux)"
[ "$(ps -p 1 -o comm=)" = "systemd" ] || fail "systemd is not PID 1: add '[boot] systemd=true' to /etc/wsl.conf and run 'wsl --shutdown' from Windows"
for bin in docker helm curl sudo ss; do
  command -v "$bin" >/dev/null 2>&1 || fail "$bin is not installed"
done
if docker info --format '{{.OperatingSystem}}' 2>/dev/null | grep -qi 'docker desktop'; then
  fail "docker points to Docker Desktop: install Docker Engine in Ubuntu and disable Desktop's WSL integration for this distro (pods must reach the databases on this host)"
fi
ok "prerequisites"

# --- Databases (out of the cluster, on this host) ----------------------------
for port in "${DB_PORTS[@]}"; do
  if ! ss -Hltn "sport = :${port}" | awk '{print $4}' | grep -Eq "^(0\.0\.0\.0|\*|\[::\]):${port}$"; then
    fail "nothing listens on 0.0.0.0:${port}: set DB_BIND_IP=0.0.0.0 in infra/local/.env and run: docker compose -f infra/local/compose.yaml --env-file infra/local/.env up -d"
  fi
done
ok "databases published on the host (${DB_PORTS[*]})"

# --- k3s ---------------------------------------------------------------------
if ! command -v k3s >/dev/null 2>&1; then
  sudo mkdir -p /etc/rancher/k3s
  sudo install -m 0600 "${SCRIPT_DIR}/k3s-config.yaml" /etc/rancher/k3s/config.yaml
  curl -sfL https://get.k3s.io | INSTALL_K3S_VERSION="${K3S_VERSION}" sh -s - server
elif ! sudo cmp -s "${SCRIPT_DIR}/k3s-config.yaml" /etc/rancher/k3s/config.yaml; then
  fail "/etc/rancher/k3s/config.yaml differs from bootstrap/k3s-config.yaml: copy it and run 'sudo systemctl restart k3s'"
fi

mkdir -p "${HOME}/.kube"
sudo install -m 0600 -o "$(id -u)" -g "$(id -g)" /etc/rancher/k3s/k3s.yaml "${HOME}/.kube/config"
export KUBECONFIG="${HOME}/.kube/config"

kubectl wait --for=condition=Ready node --all --timeout=180s >/dev/null
pod_cidr="$(kubectl get node gaze -o jsonpath='{.spec.podCIDR}')"
[ "$pod_cidr" = "10.42.0.0/24" ] || fail "node pod CIDR is ${pod_cidr}, expected 10.42.0.0/24 (external-databases points at 10.42.0.1)"
ok "k3s ${K3S_VERSION} running (pod CIDR ${pod_cidr})"

# --- Argo CD -----------------------------------------------------------------
helm repo add argo https://argoproj.github.io/argo-helm >/dev/null 2>&1 || true
helm repo update argo >/dev/null
helm upgrade --install argocd argo/argo-cd \
  --version "${ARGOCD_CHART_VERSION}" \
  --namespace argocd --create-namespace \
  --values "${SCRIPT_DIR}/argocd-values.yaml" \
  --wait
ok "Argo CD installed"

kubectl apply -f "${SCRIPT_DIR}/root-app.yaml"
ok "root Application applied: Argo CD now syncs infra/k8s/argocd from git"

node_ip="$(hostname -I | awk '{print $1}')"
cat <<EOF

Next steps
  Windows access to the Gateway (WSL does not forward ports 80/443 that k3s
  exposes through iptables), from an elevated PowerShell, after every WSL restart:
    infra\\k8s\\bootstrap\\windows-portproxy.ps1
  From this shell:  curl --resolve api.dev.gaze.localhost:443:${node_ip} -k https://api.dev.gaze.localhost/
  Argo CD UI:       kubectl -n argocd port-forward svc/argocd-server 8080:443   (https://localhost:8080)
  Admin password:   kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d
  Trust the local CA:
    kubectl -n cert-manager get secret gaze-local-ca -o jsonpath='{.data.ca\.crt}' | base64 -d > gaze-local-ca.crt
  Grafana:          kubectl -n observability port-forward svc/lgtm 3000
EOF
