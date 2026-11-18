#!/usr/bin/env bash
# Removes the local k3s cluster installed by bootstrap.sh, so it stops using
# CPU/RAM/disk and does not come back after a reboot or `wsl --shutdown`.
#
# What it removes (through k3s' own k3s-uninstall.sh):
#   - every workload: Argo CD, platform controllers, env infra, microservices
#   - the k3s systemd unit (stopped and disabled), its binaries, containerd
#     images, CNI interfaces and iptables rules
#   - the cluster state: /etc/rancher/k3s, /var/lib/rancher/k3s (including
#     local-path PersistentVolumes), /var/lib/kubelet
#   - ~/.kube/config, if it is still the copy bootstrap.sh made
#
# The databases of infra/local/compose.yaml live outside the cluster and are
# not touched.
#
# Run inside the Ubuntu WSL2 distro, from anywhere:
#   ./infra/k8s/bootstrap/teardown.sh [--yes]
# To recreate the cluster later: ./infra/k8s/bootstrap/bootstrap.sh

set -euo pipefail

UNINSTALL="/usr/local/bin/k3s-uninstall.sh"

ok()   { printf '\033[0;32m[OK]\033[0m    %s\n' "$1"; }
warn() { printf '\033[0;33m[WARN]\033[0m  %s\n' "$1"; }
fail() { printf '\033[0;31m[FAIL]\033[0m  %s\n' "$1"; exit 1; }

usage() {
  sed -n '2,/^$/s/^# \{0,1\}//p' "${BASH_SOURCE[0]}"
  exit "${1:-0}"
}

assume_yes=false
for arg in "$@"; do
  case "$arg" in
    -y|--yes)  assume_yes=true ;;
    -h|--help) usage 0 ;;
    *)         printf 'unknown option: %s\n\n' "$arg" >&2; usage 1 ;;
  esac
done

# --- Prerequisites -----------------------------------------------------------
[ "$(uname -s)" = "Linux" ] || fail "run this inside the Ubuntu WSL2 distro (where k3s is installed)"
command -v sudo >/dev/null 2>&1 || fail "sudo is not installed"

k3s_installed=false
if command -v k3s >/dev/null 2>&1 || [ -e "$UNINSTALL" ] \
   || systemctl list-unit-files k3s.service 2>/dev/null | grep -q '^k3s\.service'; then
  k3s_installed=true
fi

# --- Confirmation ------------------------------------------------------------
if [ "$assume_yes" = false ]; then
  [ -t 0 ] || fail "not running on a terminal: pass --yes to confirm"
  echo "This deletes the k3s cluster and everything in it (Argo CD, all services, PersistentVolumes)."
  read -r -p "Continue? [y/N] " answer
  case "$answer" in
    y|Y|yes|YES) ;;
    *) echo "Aborted."; exit 1 ;;
  esac
fi

# --- k3s ---------------------------------------------------------------------
if [ "$k3s_installed" = false ]; then
  ok "k3s is not installed: nothing to remove"
else
  [ -x "$UNINSTALL" ] || fail "k3s is installed but ${UNINSTALL} is missing: stop it with 'sudo systemctl disable --now k3s' and remove it by hand"

  # Decide before uninstalling: k3s.yaml disappears with the cluster.
  remove_kubeconfig=false
  if [ -f "${HOME}/.kube/config" ] && sudo test -f /etc/rancher/k3s/k3s.yaml \
     && sudo cmp -s /etc/rancher/k3s/k3s.yaml "${HOME}/.kube/config"; then
    remove_kubeconfig=true
  fi

  # Stops k3s, kills every pod (containerd shims), unmounts their volumes,
  # deletes CNI interfaces and iptables rules, disables and deletes the
  # systemd unit and removes binaries, images and cluster state.
  sudo "$UNINSTALL"

  if systemctl list-unit-files k3s.service 2>/dev/null | grep -q '^k3s\.service'; then
    fail "the k3s systemd unit still exists: check 'systemctl status k3s'"
  fi
  if pgrep -f '(^|/)k3s |containerd-shim' >/dev/null 2>&1; then
    warn "some k3s/containerd processes are still running: 'pgrep -af containerd-shim'"
  fi
  ok "k3s removed (service disabled, cluster state and images deleted)"

  if [ "$remove_kubeconfig" = true ]; then
    rm -f "${HOME}/.kube/config"
    ok "~/.kube/config removed"
  elif [ -f "${HOME}/.kube/config" ] && grep -q 'server: https://127.0.0.1:6443' "${HOME}/.kube/config"; then
    warn "~/.kube/config still points at the deleted cluster (127.0.0.1:6443) but differs from what bootstrap.sh wrote: left untouched"
  fi
fi

cat <<EOF

Next steps (Windows, optional)
  Remove the 127.0.0.1:80/443 port proxy, from an elevated PowerShell:
    infra\\k8s\\bootstrap\\windows-portproxy.ps1 -Remove
  Give the WSL VM's memory back to Windows right now:
    wsl --shutdown
EOF
