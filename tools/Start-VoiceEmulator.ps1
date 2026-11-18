[CmdletBinding()]
param(
    [string]$Avd = 'Medium_Phone',
    [ValidateRange(5554, 5682)][int]$Port = 5554,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
if ($Port % 2 -ne 0) { throw 'Choose an even emulator console port.' }
$sdkCandidates = @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME, (Join-Path $env:LOCALAPPDATA 'Android/Sdk'))
$voiceSdk = $sdkCandidates | Where-Object {
    $_ -and (Test-Path -LiteralPath (Join-Path $_ 'emulator/emulator.exe'))
} | Select-Object -First 1
if (-not $voiceSdk) { throw 'Android SDK not found. Set ANDROID_SDK_ROOT to its installed location.' }
$emulator = Join-Path $voiceSdk 'emulator/emulator.exe'
$adb = Join-Path $voiceSdk 'platform-tools/adb.exe'
if (-not $env:ANDROID_AVD_HOME) { $env:ANDROID_AVD_HOME = Join-Path $env:USERPROFILE '.android/avd' }
$available = @(& $emulator -list-avds)
if ($available -notcontains $Avd) { throw "AVD '$Avd' was not found. Available: $($available -join ', ')" }
$arguments = @('-avd', ('"' + $Avd + '"'), '-port', "$Port", '-allow-host-audio', '-no-snapshot-load', '-no-snapshot-save')
if ($DryRun) {
    Write-Output "Launch: $emulator $($arguments -join ' ')"
    return
}
$serial = "emulator-$Port"
if (@(& $adb devices) -match "^$serial\s") {
    Write-Output "$serial is already running. Close it before using this launcher to apply the audio startup flag."
    return
}
$voiceLogs = Join-Path (Split-Path $PSScriptRoot -Parent) 'build/ui-review'
New-Item -ItemType Directory -Path $voiceLogs -Force | Out-Null
# Cold boot resets the host audio connection. No wipe-data option: installed apps and Room data are retained.
# Current emulator versions zero microphone samples when -allow-host-audio is omitted.
Start-Process -FilePath $emulator -ArgumentList $arguments -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $voiceLogs 'voice-emulator-stdout.txt') `
    -RedirectStandardError (Join-Path $voiceLogs 'voice-emulator-stderr.txt') | Out-Null
Write-Output "Starting $Avd with laptop microphone input allowed."
