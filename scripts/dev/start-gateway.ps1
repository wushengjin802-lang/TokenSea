[CmdletBinding()]
param(
    [string]$ProjectRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    $ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
}

. (Join-Path $PSScriptRoot "Import-TokenSeaDevEnvironment.ps1") -ProjectRoot $ProjectRoot

$gatewayDir = Join-Path $ProjectRoot "services\gateway-runtime"
$venvPython = Join-Path $gatewayDir ".venv\Scripts\python.exe"
if (-not (Test-Path -LiteralPath $venvPython)) {
    & python -m venv (Join-Path $gatewayDir ".venv")
    if ($LASTEXITCODE -ne 0) { throw "Unable to create the Gateway virtual environment." }
    & $venvPython -m pip install -r (Join-Path $gatewayDir "requirements.txt")
    if ($LASTEXITCODE -ne 0) { throw "Unable to install Gateway dependencies." }
}

$dbPort = if ($env:TOKENSEA_POSTGRES_PORT) { $env:TOKENSEA_POSTGRES_PORT } else { "39213" }
$redisPort = if ($env:TOKENSEA_REDIS_PORT) { $env:TOKENSEA_REDIS_PORT } else { "39214" }
$runtimePort = if ($env:TOKENSEA_RUNTIME_CORE_PORT) { $env:TOKENSEA_RUNTIME_CORE_PORT } else { "39218" }
$redisPassword = [Uri]::EscapeDataString([string]$env:TOKENSEA_REDIS_PASSWORD)

$env:TOKENSEA_CONTROL_BASE = "http://localhost:39211"
$env:TOKENSEA_DB_HOST = "localhost"
$env:TOKENSEA_DB_PORT = $dbPort
$env:TOKENSEA_REDIS_URL = "redis://:$redisPassword@localhost:$redisPort/0"
$env:TOKENSEA_RUNTIME_ENGINE_URL = "http://localhost:$runtimePort"
$env:TOKENSEA_RUNTIME_CORE_BASE = "http://localhost:$runtimePort"
$env:TOKENSEA_OUTBOX_DIR = (Join-Path $gatewayDir "runtime-data\outbox")

Push-Location $gatewayDir
try {
    & $venvPython -m uvicorn app.main:app --host 0.0.0.0 --port 39212 --reload
    if ($LASTEXITCODE -ne 0) { throw "Gateway Runtime local process exited with an error." }
} finally {
    Pop-Location
}
