# Forwards Windows ports 80/443 to the k3s Gateway inside WSL2, so the
# Gateway hostnames (environments/<env>/gateway.yaml) work from Windows
# browsers/Postman and, with -Lan, from other devices on the LAN. With -Lan it
# also opens Azurite's blob port (10000, infra/local/compose.yaml) to the LAN.
#
# Needed because k3s' ServiceLB exposes 80/443 through iptables (no listening
# socket), which WSL's localhost forwarding does not see. The WSL IP changes
# on every WSL restart: run this again after `wsl --shutdown` or a reboot
# (restarting only k3s keeps it).
#
# Run from an elevated PowerShell:
#   .\infra\k8s\bootstrap\windows-portproxy.ps1          # this PC only (127.0.0.1)
#   .\infra\k8s\bootstrap\windows-portproxy.ps1 -Lan     # also other devices on the LAN
#   .\infra\k8s\bootstrap\windows-portproxy.ps1 -Remove  # after teardown.sh
#
# -Lan listens on 0.0.0.0 (127.0.0.1 keeps working) and adds inbound firewall
# rules for TCP 80/443 and 10000 limited to the local subnet and to Private /
# Domain networks. The dev hostnames (*.dev.192-168-1-135.sslip.io) resolve
# to the LAN IP, so they need -Lan even from this PC (docs/k8s.md, "Access
# from the LAN"). Running without -Lan removes the rules.
#
# Azurite is a compose container, not part of k3s. On this PC it is already
# reachable on 127.0.0.1:10000 (WSL's localhost forwarding, or Docker Desktop
# publishing it). For the LAN, a port proxy to the WSL IP is added only when
# nothing on Windows listens on 0.0.0.0:10000 yet: Docker Desktop already does,
# and a proxy on the same port would compete with it. Docker Desktop's own
# firewall rules only cover Public networks, hence the rule here.
#Requires -RunAsAdministrator
param(
    [string]$Distro = "Ubuntu",
    [switch]$Lan,
    [switch]$Remove
)

$ErrorActionPreference = "Stop"

$gatewayPorts = 80, 443
# Azurite blob service (AZURITE_BLOB_PORT in infra/local/.env).
$azuritePort = 10000
$gatewayFirewallRuleName = "Gaze k3s Gateway (LAN)"
$azuriteFirewallRuleName = "Gaze Azurite (LAN)"

function Remove-GazePortProxy {
    # Both listen addresses: switching between -Lan and localhost-only must not
    # leave the other rule behind (they would compete for the same port).
    foreach ($address in "127.0.0.1", "0.0.0.0") {
        foreach ($port in $gatewayPorts + $azuritePort) {
            # Fails harmlessly when the rule does not exist.
            netsh interface portproxy delete v4tov4 listenaddress=$address listenport=$port | Out-Null
        }
    }
}

function Remove-GazeFirewallRule {
    foreach ($name in $gatewayFirewallRuleName, $azuriteFirewallRuleName) {
        Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    }
}

function Add-GazeFirewallRule([string]$Name, [int[]]$Ports, [string]$Description) {
    if (Get-NetFirewallRule -DisplayName $Name -ErrorAction SilentlyContinue) {
        Write-Host "Firewall rule '$Name' already present"
        return
    }
    New-NetFirewallRule -DisplayName $Name -Description $Description `
        -Direction Inbound -Action Allow -Protocol TCP -LocalPort $Ports `
        -RemoteAddress LocalSubnet -Profile Private, Domain | Out-Null
    Write-Host "Firewall rule '$Name' created (TCP $($Ports -join '/'), local subnet, Private/Domain networks)"
}

function Test-WindowsListener([int]$Port) {
    # A socket on every interface (e.g. Docker Desktop's com.docker.backend
    # publishing a compose port), not just loopback.
    [bool](Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |
        Where-Object { $_.LocalAddress -in "0.0.0.0", "::" })
}

function Get-LanInterface {
    # Interfaces that are up, have a default gateway and a private (RFC 1918)
    # address: the router's network, not WSL's or Docker's virtual switches
    # nor VPNs with public ranges (e.g. Radmin VPN's 26.0.0.0/8).
    Get-NetIPConfiguration |
        Where-Object {
            $_.IPv4DefaultGateway -and $_.NetAdapter.Status -eq "Up" -and
            @($_.IPv4Address)[0].IPAddress -match '^(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.)'
        } |
        ForEach-Object {
            [pscustomobject]@{
                Ip      = @($_.IPv4Address)[0].IPAddress
                Alias   = $_.InterfaceAlias
                Index   = $_.InterfaceIndex
                Profile = (Get-NetConnectionProfile -InterfaceIndex $_.InterfaceIndex -ErrorAction SilentlyContinue).NetworkCategory
            }
        }
}

if ($Remove) {
    Remove-GazePortProxy
    Remove-GazeFirewallRule
    Write-Host "Port proxy 80/443/$azuritePort and firewall rules '$gatewayFirewallRuleName', '$azuriteFirewallRuleName' removed"
    netsh interface portproxy show v4tov4
    exit 0
}

$wslIp = ((wsl.exe -d $Distro -- hostname -I) -split '\s+' | Where-Object { $_ })[0]
if (-not $wslIp) {
    throw "Could not read the IP of WSL distro '$Distro' (is it running?)"
}

$listenAddress = "127.0.0.1"
if ($Lan) {
    $listenAddress = "0.0.0.0"
}

Remove-GazePortProxy
foreach ($port in $gatewayPorts) {
    netsh interface portproxy add v4tov4 listenaddress=$listenAddress listenport=$port connectaddress=$wslIp connectport=$port | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "netsh could not forward port $port (is something else listening on ${listenAddress}:$port, e.g. Docker Desktop or IIS?)"
    }
}
Write-Host "${listenAddress}:80/443 -> ${wslIp}:80/443 ($Distro)"

if (-not $Lan) {
    # Localhost only: close the LAN rules a previous -Lan run may have opened.
    Remove-GazeFirewallRule
    netsh interface portproxy show v4tov4
    exit 0
}

if (Test-WindowsListener $azuritePort) {
    Write-Host "Azurite: something already listens on 0.0.0.0:$azuritePort on Windows (Docker Desktop), no port proxy needed"
} else {
    if (-not (wsl.exe -d $Distro -- ss -Hltn "sport = :$azuritePort")) {
        Write-Warning "Nothing listens on port $azuritePort in '$Distro': start the compose stack (infra/local/compose.yaml) with DB_BIND_IP=0.0.0.0"
    }
    netsh interface portproxy add v4tov4 listenaddress=0.0.0.0 listenport=$azuritePort connectaddress=$wslIp connectport=$azuritePort | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "netsh could not forward port $azuritePort"
    }
    Write-Host "0.0.0.0:$azuritePort -> ${wslIp}:$azuritePort ($Distro, Azurite)"
}

Add-GazeFirewallRule -Name $gatewayFirewallRuleName -Ports $gatewayPorts `
    -Description "Inbound access from the LAN to the k3s Gateway (infra/k8s/bootstrap/windows-portproxy.ps1 -Lan)"
Add-GazeFirewallRule -Name $azuriteFirewallRuleName -Ports $azuritePort `
    -Description "Inbound access from the LAN to the compose Azurite blob service (infra/k8s/bootstrap/windows-portproxy.ps1 -Lan)"

$interfaces = @(Get-LanInterface)
if ($interfaces.Count -eq 0) {
    Write-Warning "No network interface with a default gateway is up: other devices can't reach this PC"
}
foreach ($interface in $interfaces) {
    Write-Host ""
    Write-Host "LAN IP $($interface.Ip) ($($interface.Alias), $($interface.Profile) network)"
    if ($interface.Profile -eq "Public") {
        Write-Warning "'$($interface.Alias)' is a Public network: the firewall rules do not apply there. If you trust this network, make it Private:"
        Write-Host "  Set-NetConnectionProfile -InterfaceIndex $($interface.Index) -NetworkCategory Private"
    }
    $dashed = $interface.Ip -replace '\.', '-'
    Write-Host "  Hostnames that resolve to it (sslip.io): api.dev.$dashed.sslip.io, auth.dev.$dashed.sslip.io"
    Write-Host "  Azurite blob endpoint: http://azurite.dev.$dashed.sslip.io:$azuritePort/devstoreaccount1"
}

Write-Host ""
netsh interface portproxy show v4tov4
