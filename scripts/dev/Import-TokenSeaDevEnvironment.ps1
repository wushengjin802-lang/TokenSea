param(
    [string]$ProjectRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    $ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
}

$envFile = Join-Path $ProjectRoot "deploy\compose\.env"
if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing local environment file: $envFile"
}

$loadedNames = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)

Get-Content -LiteralPath $envFile | ForEach-Object {
    $line = $_.Trim()
    if (-not $line -or $line.StartsWith("#")) {
        return
    }

    $separator = $line.IndexOf("=")
    if ($separator -le 0) {
        return
    }

    $name = $line.Substring(0, $separator).Trim()
    $value = $line.Substring($separator + 1).Trim()
    if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
        $value = $value.Substring(1, $value.Length - 2)
    }
    Set-Item -Path "Env:$name" -Value $value
    [void]$loadedNames.Add($name)
}

# Docker Compose exposes the shared Runtime Core key to application processes
# under TOKENSEA_RUNTIME_ENGINE_KEY. When the local .env does not explicitly
# define an engine key, override any stale inherited process value with the
# current Runtime Core key so repeated hybrid restarts stay deterministic.
$engineKeyConfiguredInFile = $loadedNames.Contains("TOKENSEA_RUNTIME_ENGINE_KEY") -and
    -not [string]::IsNullOrWhiteSpace($env:TOKENSEA_RUNTIME_ENGINE_KEY)
if (-not $engineKeyConfiguredInFile -and
    -not [string]::IsNullOrWhiteSpace($env:TOKENSEA_RUNTIME_CORE_KEY)) {
    $env:TOKENSEA_RUNTIME_ENGINE_KEY = $env:TOKENSEA_RUNTIME_CORE_KEY
}
