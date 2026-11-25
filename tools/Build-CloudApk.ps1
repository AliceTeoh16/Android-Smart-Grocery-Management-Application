[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$ServerUrl, [switch]$Offline,
    [ValidateSet('turso','sqlite','any')][string]$ExpectedStorage = 'turso')

$ErrorActionPreference = 'Stop'
$cloudRoot = Split-Path -Parent $PSScriptRoot
$cloudUri = $null
if (-not [Uri]::TryCreate($ServerUrl, [UriKind]::Absolute, [ref]$cloudUri) -or
    $cloudUri.Scheme -ne 'https' -or -not $cloudUri.Host -or $cloudUri.UserInfo -or
    $cloudUri.Query -or $cloudUri.Fragment -or $cloudUri.AbsolutePath -ne '/') {
    throw 'Use the HTTPS server origin only, such as https://your-service.onrender.com/.'
}
$cloudUrl = $cloudUri.GetLeftPart([UriPartial]::Authority) + '/'
Write-Host 'Checking the hosted service. A sleeping free service can take about a minute to start.'
try { $cloudHealth = Invoke-RestMethod -Uri ($cloudUrl + 'health') -TimeoutSec 90 }
catch { throw 'Cannot verify the hosted service. Confirm its HTTPS address and deployment status before building.' }
if ($cloudHealth.service -eq 'shelfwise-import') {
    throw 'Complete the database import and set SHARING_IMPORT_MODE=false before building the APK.'
}
if ($cloudHealth.ok -ne $true -or $cloudHealth.service -ne 'shelfwise-gateway' -or $cloudHealth.sharing -ne 'household-sharing') {
    throw 'The address does not identify a healthy ShelfWise gateway and sharing service.'
}
if ($ExpectedStorage -ne 'any' -and $cloudHealth.sharingStorage -ne $ExpectedStorage) {
    throw "The hosted service is not using the expected $ExpectedStorage storage. Check its database configuration."
}
$cloudJbr = 'C:\Program Files\Android\Android Studio\jbr'
if (-not $env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $cloudJbr 'bin/java.exe'))) { $env:JAVA_HOME = $cloudJbr }
$cloudArguments = @(':app:assembleDebug', '--console=plain', "-PAPP_BASE_URL=$cloudUrl")
if ($Offline) { $cloudArguments += '--offline' }
Push-Location $cloudRoot
try {
    & (Join-Path $cloudRoot 'gradlew.bat') @cloudArguments
    if ($LASTEXITCODE -ne 0) { throw 'Cloud APK build failed.' }
} finally { Pop-Location }
$cloudConfig = Get-Content (Join-Path $cloudRoot 'app/build/generated/source/buildConfig/debug/com/xmum/smartgrocery/BuildConfig.java') -Raw
foreach ($cloudField in @('AI_BASE_URL', 'SHARING_BASE_URL')) {
    if ($cloudConfig -notmatch ($cloudField + '\s*=\s*"' + [regex]::Escape($cloudUrl) + '"')) {
        throw 'The generated server address does not match the requested HTTPS origin.'
    }
}
if ($cloudConfig -notmatch 'MANAGED_SERVER\s*=\s*true') { throw 'The APK must use the bundled server address.' }
$cloudOutput = Join-Path $cloudRoot 'build/cloud-deploy'
New-Item -ItemType Directory -Path $cloudOutput -Force | Out-Null
$cloudApk = Join-Path $cloudOutput 'ShelfWise-cloud.apk'
Copy-Item -LiteralPath (Join-Path $cloudRoot 'app/build/outputs/apk/debug/app-debug.apk') -Destination $cloudApk -Force
[ordered]@{ serverUrl=$cloudUrl; serverVerified=$true; sha256=(Get-FileHash -LiteralPath $cloudApk).Hash; builtAt=[DateTime]::UtcNow.ToString('o') } |
    ConvertTo-Json | Set-Content (Join-Path $cloudOutput 'apk.json') -Encoding UTF8
Write-Host "Cloud APK: $cloudApk"
Write-Host "Server address: $cloudUrl"
