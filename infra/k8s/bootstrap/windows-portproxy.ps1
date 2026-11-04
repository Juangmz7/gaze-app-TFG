# Forwards Windows 127.0.0.1:80/443 to the k3s Gateway inside WSL2, so
# https://api.dev.gaze.localhost and https://auth.dev.gaze.localhost work from
# Windows browsers/Postman.
#
# Needed because k3s' ServiceLB exposes 80/443 through iptables (no listening
# socket), which WSL's localhost forwarding does not see. The WSL IP changes
# on every WSL restart: run this again after `wsl --shutdown` or a reboot.
#
# Run from an elevated PowerShell:  .\infra\k8s\bootstrap\windows-portproxy.ps1
#Requires -RunAsAdministrator
param(
    [string]$Distro = "Ubuntu"
)

$ErrorActionPreference = "Stop"

$wslIp = ((wsl.exe -d $Distro -- hostname -I) -split '\s+' | Where-Object { $_ })[0]
if (-not $wslIp) {
    throw "Could not read the IP of WSL distro '$Distro' (is it running?)"
}

foreach ($port in 80, 443) {
    # Fails harmlessly when no rule exists yet.
    netsh interface portproxy delete v4tov4 listenaddress=127.0.0.1 listenport=$port | Out-Null
    netsh interface portproxy add v4tov4 listenaddress=127.0.0.1 listenport=$port connectaddress=$wslIp connectport=$port | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "netsh could not forward port $port (is something else listening on 127.0.0.1:$port, e.g. Docker Desktop or IIS?)"
    }
}

Write-Host "127.0.0.1:80/443 -> ${wslIp}:80/443 ($Distro)"
netsh interface portproxy show v4tov4
