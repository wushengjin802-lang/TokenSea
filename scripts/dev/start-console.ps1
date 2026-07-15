[CmdletBinding()]
param(
    [string]$ProjectRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    $ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
}

$env:VITE_API_BASE = "http://localhost:39211"
$env:VITE_GATEWAY_BASE = "http://localhost:39212"

Push-Location $ProjectRoot
try {
    & npm run console:dev
    if ($LASTEXITCODE -ne 0) { throw "Console local process exited with an error." }
} finally {
    Pop-Location
}
