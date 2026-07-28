[CmdletBinding()]
param(
    [string]$GatewayBase = $(if ($env:TOKENSEA_GATEWAY_BASE) { $env:TOKENSEA_GATEWAY_BASE } else { "http://localhost:39212" }),
    [string]$ApiKey = $env:TOKENSEA_API_KEY,
    [string]$Model = $(if ($env:TOKENSEA_MODEL) { $env:TOKENSEA_MODEL } else { "deepseek-v4-pro" }),
    [string]$Prompt = "Reply exactly: TokenSea key verification succeeded.",
    [switch]$SkipChat,
    [int]$TimeoutSec = 60
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Convert-SecureStringToPlainText {
    param([Parameter(Mandatory = $true)][Security.SecureString]$SecureValue)

    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecureValue)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

function Get-HttpErrorDetail {
    param([Parameter(Mandatory = $true)]$ErrorRecord)

    $statusCode = $null
    $responseBody = $null
    $response = $ErrorRecord.Exception.Response

    if ($null -ne $response) {
        try {
            if ($null -ne $response.StatusCode) {
                $statusCode = [int]$response.StatusCode
            }
        } catch {}

        try {
            if ($null -ne $response.Content) {
                $responseBody = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            }
        } catch {}

        if ([string]::IsNullOrWhiteSpace($responseBody)) {
            try {
                $stream = $response.GetResponseStream()
                if ($null -ne $stream) {
                    $reader = [IO.StreamReader]::new($stream)
                    try { $responseBody = $reader.ReadToEnd() } finally { $reader.Dispose() }
                }
            } catch {}
        }
    }

    if (-not [string]::IsNullOrWhiteSpace($responseBody)) {
        try {
            $json = $responseBody | ConvertFrom-Json
            $message = $json.message
            if ([string]::IsNullOrWhiteSpace($message)) { $message = $json.detail }
            if ([string]::IsNullOrWhiteSpace($message) -and $null -ne $json.error) {
                $message = $json.error.message
            }
            if (-not [string]::IsNullOrWhiteSpace($message)) {
                return "HTTP ${statusCode}: $message"
            }
        } catch {}
        return "HTTP ${statusCode}: $responseBody"
    }

    if ($null -ne $statusCode) {
        return "HTTP ${statusCode}: $($ErrorRecord.Exception.Message)"
    }
    return $ErrorRecord.Exception.Message
}

function Invoke-TokenSeaJson {
    param(
        [Parameter(Mandatory = $true)][ValidateSet("GET", "POST")][string]$Method,
        [Parameter(Mandatory = $true)][string]$Uri,
        [hashtable]$Headers,
        [string]$Body
    )

    $parameters = @{
        Method      = $Method
        Uri         = $Uri
        TimeoutSec  = $TimeoutSec
        ErrorAction = "Stop"
    }
    if ($null -ne $Headers) { $parameters.Headers = $Headers }
    if (-not [string]::IsNullOrWhiteSpace($Body)) {
        $parameters.Body = $Body
        $parameters.ContentType = "application/json; charset=utf-8"
    }

    try {
        return Invoke-RestMethod @parameters
    } catch {
        throw (Get-HttpErrorDetail -ErrorRecord $_)
    }
}

try {
    $GatewayBase = $GatewayBase.TrimEnd("/")
    if ($GatewayBase.EndsWith("/v1", [StringComparison]::OrdinalIgnoreCase)) {
        $ApiBase = $GatewayBase
        $GatewayRoot = $GatewayBase.Substring(0, $GatewayBase.Length - 3)
    } else {
        $GatewayRoot = $GatewayBase
        $ApiBase = "$GatewayBase/v1"
    }

    if ([string]::IsNullOrWhiteSpace($ApiKey)) {
        $secureKey = Read-Host "Enter TokenSea Virtual Key" -AsSecureString
        $ApiKey = Convert-SecureStringToPlainText -SecureValue $secureKey
    }
    if ([string]::IsNullOrWhiteSpace($ApiKey)) {
        throw "Virtual Key is required."
    }

    $headers = @{ Authorization = "Bearer $ApiKey" }

    Write-Host "[1/3] Checking Gateway health: $GatewayRoot/health"
    $health = Invoke-TokenSeaJson -Method GET -Uri "$GatewayRoot/health"
    if ($health.status -ne "ok" -and $health.status -ne "UP") {
        Write-Warning "Gateway returned a non-standard health response: $($health | ConvertTo-Json -Compress)"
    } else {
        Write-Host "      Gateway status: $($health.status)"
    }

    Write-Host "[2/3] Listing models allowed by this Virtual Key: $ApiBase/models"
    $modelsResponse = Invoke-TokenSeaJson -Method GET -Uri "$ApiBase/models" -Headers $headers
    $modelRows = @()
    if ($null -ne $modelsResponse.data) {
        $modelRows = @($modelsResponse.data)
    } elseif ($modelsResponse -is [System.Collections.IEnumerable] -and -not ($modelsResponse -is [string])) {
        $modelRows = @($modelsResponse)
    }

    $modelIds = @(
        $modelRows | ForEach-Object {
            if ($_ -is [string]) { $_ }
            elseif ($null -ne $_.id) { [string]$_.id }
            elseif ($null -ne $_.model) { [string]$_.model }
        } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object -Unique
    )

    if ($modelIds.Count -eq 0) {
        throw "The Virtual Key is valid, but /v1/models returned no accessible models. Check model_scope, tenant scope, and platform-model visibility."
    }

    Write-Host "      Accessible models:"
    $modelIds | ForEach-Object { Write-Host "      - $_" }

    if ($modelIds -notcontains $Model) {
        throw "The Virtual Key cannot access model '$Model'. Select a model from the list above or update the key model_scope."
    }

    if ($SkipChat) {
        Write-Host "Verification passed: the Virtual Key is valid and can access '$Model'. Chat call skipped."
        exit 0
    }

    Write-Host "[3/3] Calling model: $Model"
    $requestBody = @{
        model = $Model
        messages = @(
            @{ role = "user"; content = $Prompt }
        )
        temperature = 0
        max_tokens = 64
        stream = $false
    } | ConvertTo-Json -Depth 8

    $completion = Invoke-TokenSeaJson -Method POST -Uri "$ApiBase/chat/completions" -Headers $headers -Body $requestBody
    $content = $completion.choices[0].message.content
    if ([string]::IsNullOrWhiteSpace([string]$content)) {
        throw "The request succeeded, but choices[0].message.content was empty. Response: $($completion | ConvertTo-Json -Depth 10 -Compress)"
    }

    Write-Host "      Model response: $content"
    if ($null -ne $completion.usage) {
        Write-Host "      Token usage: prompt=$($completion.usage.prompt_tokens), completion=$($completion.usage.completion_tokens), total=$($completion.usage.total_tokens)"
    }
    if ($null -ne $completion.id) {
        Write-Host "      Request id: $($completion.id)"
    }

    Write-Host "Verification passed: Virtual Key and model '$Model' completed a real request."
    exit 0
} catch {
    Write-Error "Verification failed: $($_.Exception.Message)"
    exit 1
} finally {
    $ApiKey = $null
}
