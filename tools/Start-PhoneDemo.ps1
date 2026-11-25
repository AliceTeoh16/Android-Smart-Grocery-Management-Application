[CmdletBinding()]
param(
    [string]$ComputerIp,
    [string]$InterfaceAlias = 'Wi-Fi',
    [switch]$BuildApk,
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$demoRoot = Split-Path -Parent $PSScriptRoot
$demoOutput = Join-Path $demoRoot 'build/phone-demo'
New-Item -ItemType Directory -Path $demoOutput -Force | Out-Null

$demoNetwork = Get-NetIPConfiguration -InterfaceAlias $InterfaceAlias
if (-not $ComputerIp) {
    $ComputerIp = @($demoNetwork.IPv4Address | Where-Object {
        $_.IPAddress -and $_.IPAddress -notlike '169.254.*'
    } | Select-Object -ExpandProperty IPAddress)[0]
}
$demoAddress = $null
if (-not [Net.IPAddress]::TryParse($ComputerIp, [ref]$demoAddress) -or
    $demoAddress.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork -or
    @($demoNetwork.IPv4Address.IPAddress) -notcontains $ComputerIp) {
    throw 'Choose an IPv4 address assigned to the connected Wi-Fi adapter.'
}
$demoBytes = $demoAddress.GetAddressBytes()
if (-not ($demoBytes[0] -eq 10 -or ($demoBytes[0] -eq 192 -and $demoBytes[1] -eq 168) -or
    ($demoBytes[0] -eq 172 -and $demoBytes[1] -ge 16 -and $demoBytes[1] -le 31))) {
    throw 'This launcher is for a private same-Wi-Fi address.'
}
$demoNode = (Get-Command node -ErrorAction Stop).Source
$demoAiUrl = "http://${ComputerIp}:8787/"
$demoSharingUrl = "http://${ComputerIp}:8790/"
$demoAppUrl = "http://${ComputerIp}:8788/"

function Get-DemoHealth([string]$Url) {
    try { Invoke-RestMethod -Uri ($Url + 'health') -TimeoutSec 3 } catch { $null }
}

function Start-DemoService([string]$Name, [int]$Port, [string]$Directory, [string]$Url) {
    $demoHealth = Get-DemoHealth $Url
    if (-not $demoHealth) {
        $demoListener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
        if ($demoListener) { throw "Port $Port is occupied but $Name is not healthy. Existing processes were left running." }
        $demoOldPort = $env:PORT
        $demoOldSharingPort = $env:SHARING_PORT
        $demoOldSharingHost = $env:SHARING_HOST
        try {
            if ($Name -eq 'sharing') { $env:SHARING_PORT = "$Port"; $env:SHARING_HOST = '0.0.0.0' }
            else { $env:PORT = "$Port" }
            $demoProcess = Start-Process -FilePath $demoNode -ArgumentList @('server.js') -WorkingDirectory $Directory -WindowStyle Hidden `
                -RedirectStandardOutput (Join-Path $demoOutput "$Name.out.log") `
                -RedirectStandardError (Join-Path $demoOutput "$Name.err.log") -PassThru
            $demoProcess.Id | Set-Content -LiteralPath (Join-Path $demoOutput "$Name.pid")
        } finally {
            $env:PORT = $demoOldPort
            $env:SHARING_PORT = $demoOldSharingPort
            $env:SHARING_HOST = $demoOldSharingHost
        }
        for ($demoAttempt = 0; $demoAttempt -lt 12 -and -not $demoHealth; $demoAttempt++) {
            Start-Sleep -Milliseconds 300
            $demoHealth = Get-DemoHealth $Url
        }
    }
    if (-not $demoHealth) { throw "$Name did not start. Check build/phone-demo/$Name.err.log." }
    if ($Name -eq 'ai' -and ($demoHealth.ok -ne $true -or $demoHealth.provider -ne 'gemini')) {
        throw 'The AI port does not identify the existing ShelfWise backend.'
    }
    if ($Name -eq 'sharing' -and $demoHealth.service -ne 'household-sharing') {
        throw 'The sharing port does not identify the ShelfWise household service.'
    }
    if ($Name -eq 'gateway' -and ($demoHealth.service -ne 'shelfwise-gateway' -or $demoHealth.ok -ne $true)) {
        throw 'The gateway is not connected to both ShelfWise services.'
    }
    Write-Host "$Name service ready: ${Url}health"
}

# Existing backend code, prompts, models, API keys and database contents are unchanged.
Start-DemoService 'ai' 8787 (Join-Path $demoRoot 'backend') $demoAiUrl
Start-DemoService 'sharing' 8790 (Join-Path $demoRoot 'backend/sharing') $demoSharingUrl
Start-DemoService 'gateway' 8788 (Join-Path $demoRoot 'backend/gateway') $demoAppUrl

if ($BuildApk) {
    $demoJbr = 'C:\Program Files\Android\Android Studio\jbr'
    if (-not $env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $demoJbr 'bin/java.exe'))) {
        $env:JAVA_HOME = $demoJbr
    }
    $demoArguments = @(':app:assembleDebug', '--console=plain', "-PAPP_BASE_URL=$demoAppUrl")
    if ($Offline) { $demoArguments += '--offline' }
    Push-Location $demoRoot
    try {
        & (Join-Path $demoRoot 'gradlew.bat') @demoArguments
        if ($LASTEXITCODE -ne 0) { throw 'The phone APK build failed. No phone APK was replaced.' }
    } finally { Pop-Location }
    if (@((Get-NetIPConfiguration -InterfaceAlias $InterfaceAlias).IPv4Address.IPAddress) -notcontains $ComputerIp) {
        throw 'The Wi-Fi address changed during the build. Run this launcher again to build for the current network.'
    }
    $demoConfigPath = Join-Path $demoRoot 'app/build/generated/source/buildConfig/debug/com/xmum/smartgrocery/BuildConfig.java'
    $demoConfig = Get-Content -LiteralPath $demoConfigPath -Raw
    if ($demoConfig -notmatch ('AI_BASE_URL\s*=\s*"' + [regex]::Escape($demoAppUrl) + '"') -or
        $demoConfig -notmatch ('SHARING_BASE_URL\s*=\s*"' + [regex]::Escape($demoAppUrl) + '"') -or
        $demoConfig -notmatch 'MANAGED_SERVER\s*=\s*true') {
        throw 'The generated APK addresses do not match this Wi-Fi connection.'
    }
    Copy-Item -LiteralPath (Join-Path $demoRoot 'app/build/outputs/apk/debug/app-debug.apk') `
        -Destination (Join-Path $demoOutput 'ShelfWise-phone-debug.apk.pending') -Force
    Move-Item -LiteralPath (Join-Path $demoOutput 'ShelfWise-phone-debug.apk.pending') `
        -Destination (Join-Path $demoOutput 'ShelfWise-phone-debug.apk') -Force
    Copy-Item -LiteralPath (Join-Path $demoOutput 'ShelfWise-phone-debug.apk') `
        -Destination (Join-Path $demoOutput 'ShelfWise-1.1-phone.apk') -Force
}

$demoInfo = [ordered]@{
    computerIp = $ComputerIp
    interfaceAlias = $InterfaceAlias
    appBaseUrl = $demoAppUrl
    aiBaseUrl = $demoAppUrl
    sharingBaseUrl = $demoAppUrl
    version = '1.1'
    generatedAt = [DateTime]::UtcNow.ToString('o')
    apk = 'build/phone-demo/ShelfWise-phone-debug.apk'
    builtThisRun = [bool]$BuildApk
}
$demoInfo | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $demoOutput 'connection.json') -Encoding UTF8
@"
ShelfWise same-Wi-Fi phone demo

Computer Wi-Fi address: $ComputerIp
ShelfWise service (accounts, household sharing, AI): $demoAppUrl

1. Connect the phone and computer to the same Wi-Fi. Keep the computer awake.
2. Open ${demoAppUrl}health in the phone's browser. It must show ok: true.
3. Download ${demoAppUrl}download/shelfwise.apk on the phone and install it as an update.
   The same APK is saved as build/phone-demo/ShelfWise-1.1-phone.apk.
4. The address is configured automatically, including after updating an older installation. Do not uninstall or clear app storage.
5. Sign in with the existing ShelfWise account. This uses the existing sharing database.

If browser checks fail, run Allow-Phone-WiFi.bat and approve the Windows administrator prompt.
After code updates, run Start-Phone-Demo.bat again to rebuild.
If the Wi-Fi IP changes, run both Allow-Phone-WiFi.bat and Start-Phone-Demo.bat again.
For router guest/client isolation, use a Wi-Fi network that permits devices to communicate.
"@ | Set-Content -LiteralPath (Join-Path $demoOutput 'phone-instructions.txt') -Encoding UTF8
Write-Host "ShelfWise phone address (configured automatically): $demoAppUrl"
Write-Host "Phone APK: $(Join-Path $demoOutput 'ShelfWise-phone-debug.apk')"
Write-Host "Phone download link: ${demoAppUrl}download/shelfwise.apk"
Write-Host 'If Windows has not yet allowed these ports, run Allow-Phone-WiFi.bat once.'
