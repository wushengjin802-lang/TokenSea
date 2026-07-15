[CmdletBinding()]
param(
    [string]$ProjectRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    $ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
}

$composeDir = Join-Path $ProjectRoot "deploy\compose"
Push-Location $composeDir
try {
    # Local processes own 39210, 39211 and 39212 in development mode.
    & docker compose -p tokensea --env-file ./.env -f docker-compose.yml -f docker-compose.dev.yml rm -sf `
        tokensea-console tokensea-control-plane tokensea-gateway-runtime
    if ($LASTEXITCODE -ne 0) { throw "Unable to stop Docker application services." }

    & docker compose -p tokensea --env-file ./.env -f docker-compose.yml -f docker-compose.dev.yml up -d `
        tokensea-postgres tokensea-redis tokensea-egress-proxy tokensea-runtime-core
    if ($LASTEXITCODE -ne 0) { throw "Unable to start Docker infrastructure." }
} finally {
    Pop-Location
}
