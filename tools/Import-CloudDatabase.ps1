[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$DatabaseUrl, [string]$SnapshotFile)

$ErrorActionPreference = 'Stop'
$importRoot = Split-Path -Parent $PSScriptRoot
$importNode = (Get-Command node -ErrorAction Stop).Source
$importSharing = Join-Path $importRoot 'backend/sharing'
if (-not (Test-Path -LiteralPath (Join-Path $importSharing 'node_modules/@tursodatabase/serverless'))) {
    & npm.cmd --prefix $importSharing ci --omit=dev --ignore-scripts --no-audit --no-fund
    if ($LASTEXITCODE -ne 0) { throw 'The database driver could not be installed.' }
}
if (-not $SnapshotFile) {
    $importOutput = Join-Path $importRoot 'build/cloud-deploy'
    New-Item -ItemType Directory -Path $importOutput -Force | Out-Null
    $SnapshotFile = Join-Path $importOutput ('households-transfer-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff') + '.sqlite')
    & $importNode (Join-Path $importRoot 'backend/deploy/backup-sharing.mjs') `
        (Join-Path $importSharing 'data/households.sqlite') $SnapshotFile
    if ($LASTEXITCODE -ne 0) { throw 'A consistent server database backup could not be created.' }
}
$SnapshotFile = (Resolve-Path -LiteralPath $SnapshotFile).Path
Write-Host "Private backup for this import: $SnapshotFile"
Write-Host 'The target database must be empty and Render must still be in setup/import mode.'
$importSecureToken = Read-Host 'Paste the Turso database auth token (hidden)' -AsSecureString
$importPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($importSecureToken)
$importPreviousUrl = $env:TURSO_DATABASE_URL
$importPreviousToken = $env:TURSO_AUTH_TOKEN
try {
    $env:TURSO_DATABASE_URL = $DatabaseUrl
    $env:TURSO_AUTH_TOKEN = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($importPointer)
    & $importNode (Join-Path $importRoot 'backend/deploy/import-turso.mjs') $SnapshotFile
    if ($LASTEXITCODE -ne 0) { throw 'Import could not be confirmed. Keep setup mode enabled and retry using the printed snapshot path.' }
} finally {
    $env:TURSO_DATABASE_URL = $importPreviousUrl
    $env:TURSO_AUTH_TOKEN = $importPreviousToken
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($importPointer)
}
