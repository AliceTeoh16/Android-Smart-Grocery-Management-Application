[CmdletBinding()]
param([string]$InterfaceAlias = 'Wi-Fi', [string]$ComputerIp)

$ErrorActionPreference = 'Stop'
$firewallRoot = Split-Path -Parent $PSScriptRoot
$firewallOutput = Join-Path $firewallRoot 'build/phone-demo'
New-Item -ItemType Directory -Path $firewallOutput -Force | Out-Null
$firewallResultPath = Join-Path $firewallOutput 'firewall-result.json'
try {
    $firewallAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)
    if (-not $firewallAdmin) {
        $firewallArguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $PSCommandPath + '"'),
            '-InterfaceAlias', ('"' + $InterfaceAlias + '"'))
        if ($ComputerIp) { $firewallArguments += @('-ComputerIp', ('"' + $ComputerIp + '"')) }
        $firewallElevated = Start-Process -FilePath powershell.exe -Verb RunAs -WindowStyle Hidden `
            -ArgumentList $firewallArguments -PassThru
        $firewallElevated.WaitForExit()
        if ($firewallElevated.ExitCode -ne 0) { throw 'The elevated firewall setup did not complete. See firewall-result.json.' }
        return
    }
    $firewallAddress = $ComputerIp
    if (-not $firewallAddress) {
        $firewallAddress = Get-NetIPConfiguration -InterfaceAlias $InterfaceAlias |
            ForEach-Object { $_.IPv4Address } | Where-Object { $_.IPAddress -and $_.IPAddress -notlike '169.254.*' } |
            Select-Object -First 1 -ExpandProperty IPAddress
    }
    if (-not $firewallAddress) { throw 'No connected Wi-Fi IPv4 address was found.' }
    $firewallParsedIp = $null
    if (-not [Net.IPAddress]::TryParse($firewallAddress, [ref]$firewallParsedIp) -or
        $firewallParsedIp.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork) { throw 'Use a private IPv4 address.' }
    $firewallBytes = $firewallParsedIp.GetAddressBytes()
    if (-not ($firewallBytes[0] -eq 10 -or ($firewallBytes[0] -eq 192 -and $firewallBytes[1] -eq 168) -or
        ($firewallBytes[0] -eq 172 -and $firewallBytes[1] -ge 16 -and $firewallBytes[1] -le 31))) { throw 'Use a private IPv4 address.' }
    $firewallNode = (Get-Command node -ErrorAction Stop).Source
    $firewallRuleName = 'ShelfWise-Phone-Demo-LAN'
    $firewallOldDescription = 'Managed by ShelfWise phone demo: Node TCP 8787 and 8790, current Wi-Fi address, local subnet only.'
    $firewallDescription = 'Managed by ShelfWise phone demo: Node TCP 8788 gateway, current Wi-Fi address, local subnet only.'
    $firewallExisting = Get-NetFirewallRule -Name $firewallRuleName -ErrorAction SilentlyContinue
    if ($firewallExisting) {
        if (@($firewallDescription, $firewallOldDescription) -notcontains $firewallExisting.Description) { throw 'An unrelated firewall rule has this name; it was left unchanged.' }
        Remove-NetFirewallRule -Name $firewallRuleName
    }
    New-NetFirewallRule -Name $firewallRuleName -DisplayName 'ShelfWise phone demo - same Wi-Fi only' `
        -Description $firewallDescription -Direction Inbound -Action Allow -Enabled True -Profile Any `
        -InterfaceAlias $InterfaceAlias -LocalAddress $firewallAddress -RemoteAddress LocalSubnet `
        -Protocol TCP -LocalPort 8788 -Program $firewallNode | Out-Null
    [ordered]@{ ok = $true; computerIp = $firewallAddress; ports = @(8788); rule = $firewallRuleName } |
        ConvertTo-Json | Set-Content -LiteralPath $firewallResultPath -Encoding UTF8
} catch {
    [ordered]@{ ok = $false; error = $_.Exception.Message } | ConvertTo-Json |
        Set-Content -LiteralPath $firewallResultPath -Encoding UTF8
    throw
}
