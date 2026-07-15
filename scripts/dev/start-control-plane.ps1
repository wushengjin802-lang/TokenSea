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

$javaHome = "C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot"
if (-not (Test-Path -LiteralPath $javaHome)) {
    throw "JDK 21 was not found: $javaHome"
}
$env:JAVA_HOME = $javaHome
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

$dbPort = if ($env:TOKENSEA_POSTGRES_PORT) { $env:TOKENSEA_POSTGRES_PORT } else { "39213" }
$redisPort = if ($env:TOKENSEA_REDIS_PORT) { $env:TOKENSEA_REDIS_PORT } else { "39214" }
$runtimePort = if ($env:TOKENSEA_RUNTIME_CORE_PORT) { $env:TOKENSEA_RUNTIME_CORE_PORT } else { "39218" }
$egressPort = if ($env:TOKENSEA_EGRESS_PROXY_PORT) { $env:TOKENSEA_EGRESS_PROXY_PORT } else { "18080" }

$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:$dbPort/$env:TOKENSEA_DB_NAME"
$env:SPRING_DATASOURCE_USERNAME = $env:TOKENSEA_DB_USER
$env:SPRING_DATASOURCE_PASSWORD = $env:TOKENSEA_DB_PASSWORD
$env:SPRING_DATA_REDIS_HOST = "localhost"
$env:SPRING_DATA_REDIS_PORT = $redisPort
$env:SPRING_DATA_REDIS_PASSWORD = $env:TOKENSEA_REDIS_PASSWORD
$env:TOKENSEA_RUNTIME_ENGINE_URL = "http://localhost:$runtimePort"
$env:TOKENSEA_EGRESS_PROXY_HOST = "localhost"
$env:TOKENSEA_EGRESS_PROXY_PORT = $egressPort
$env:TOKENSEA_CORS_ALLOWED_ORIGINS = "http://localhost:39210"

Push-Location (Join-Path $ProjectRoot "services\control-plane")
try {
    # Run the packaged JAR to avoid Maven's forked-JVM classpath encoding issue on Chinese workspace paths.
    & mvn "-DskipTests" package
    if ($LASTEXITCODE -ne 0) { throw "Unable to package the Control Plane." }
    & java -jar ".\target\control-plane-0.1.0.jar"
    if ($LASTEXITCODE -ne 0) { throw "Control Plane local process exited with an error." }
} finally {
    Pop-Location
}
