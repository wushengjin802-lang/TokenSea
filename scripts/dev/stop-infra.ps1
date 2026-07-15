[CmdletBinding()]
param(
    [string]$ProjectRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    $ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
}

Push-Location (Join-Path $ProjectRoot "deploy\compose")
try {
    & docker compose -p tokensea --env-file ./.env -f docker-compose.yml -f docker-compose.dev.yml stop `
        tokensea-postgres tokensea-redis tokensea-egress-proxy tokensea-runtime-core
    if ($LASTEXITCODE -ne 0) { throw "Unable to stop Docker infrastructure." }
} finally {
    Pop-Location
}
