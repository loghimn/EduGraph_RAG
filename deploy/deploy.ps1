#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$EnvFile,
    [Parameter(Mandatory)][ValidatePattern('^sha-[0-9a-f]{40}$')][string]$BackendTag,
    [Parameter(Mandatory)][ValidatePattern('^sha-[0-9a-f]{40}$')][string]$FrontendTag,
    [switch]$Gpu,
    [switch]$Tunnel,
    [switch]$ValidateOnly
)
$ErrorActionPreference = 'Stop'
$EnvFile = (Resolve-Path -LiteralPath $EnvFile).Path
$stateFile = Join-Path (Split-Path $EnvFile -Parent) 'current-release.json'
$previousFile = Join-Path (Split-Path $EnvFile -Parent) 'previous-release.json'
$composeArgs = @('compose', '--project-name', 'edugraph', '--env-file', $EnvFile,
    '-f', (Join-Path $PSScriptRoot 'compose.yml'))
if ($Gpu) { $composeArgs += @('-f', (Join-Path $PSScriptRoot 'compose.gpu.yml')) }
if ($Tunnel) { $composeArgs += @('--profile', 'tunnel') }

function Invoke-Compose([string[]]$Arguments) {
    & docker @composeArgs @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose failed: $($Arguments[0])" }
}

function Test-Origin {
    foreach ($path in @('/healthz', '/courses')) {
        $response = Invoke-WebRequest "http://127.0.0.1:8088$path" -TimeoutSec 15
        if ($response.StatusCode -ne 200) { throw "Origin check failed: $path" }
        if ($path -eq '/courses' -and $response.Content -notmatch '<html') {
            throw 'SPA fallback did not return HTML'
        }
    }
    $response = Invoke-WebRequest 'http://127.0.0.1:8088/api/users/me' -SkipHttpErrorCheck -TimeoutSec 15
    if ($response.StatusCode -ne 401) { throw 'Protected gateway API did not return 401' }
}

$oldBackend = $env:BACKEND_TAG
$oldFrontend = $env:FRONTEND_TAG
try {
    $env:BACKEND_TAG = $BackendTag
    $env:FRONTEND_TAG = $FrontendTag
    # Quiet validation: rendered Compose output contains credentials.
    Invoke-Compose @('config', '--quiet')
    if ($ValidateOnly) { Write-Host 'Compose configuration is valid.'; return }
    # Keep rendered secrets in memory only; do not print this configuration.
    $rendered = & docker @composeArgs config --format json
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read Compose configuration.' }
    $configuration = $rendered | ConvertFrom-Json
    foreach ($service in $configuration.services.PSObject.Properties) {
        if ($service.Value.image -match 'CHANGE_ME') { throw 'Replace image registry placeholders in the environment file.' }
        foreach ($setting in $service.Value.environment.PSObject.Properties) {
            if ([string]$setting.Value -match 'CHANGE_ME') { throw "Replace environment placeholder: $($setting.Name)" }
        }
    }
    try {
        $jwtBytes = [Convert]::FromBase64String($configuration.services.'core-service'.environment.JWT_SECRET)
        if ($jwtBytes.Length -lt 32) { throw 'too short' }
    } catch { throw 'JWT_SECRET must be Base64 encoding of at least 32 random bytes.' }
    if ($Tunnel -and -not $configuration.services.cloudflared.environment.TUNNEL_TOKEN) {
        throw 'Tunnel mode requires CLOUDFLARE_TUNNEL_TOKEN in the server environment file.'
    }
    $previous = if (Test-Path -LiteralPath $stateFile) {
        Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
    } else { $null }

    # Pull all images successfully before replacing any running service.
    Invoke-Compose @('pull')
    try {
        Invoke-Compose @('up', '-d', '--wait', '--wait-timeout', '1200', '--remove-orphans')
        Test-Origin
    } catch {
        $failure = $_
        if ($null -ne $previous) {
            Write-Warning 'Deployment failed. Restoring previous image versions and deployment mode.'
            $env:BACKEND_TAG = $previous.backendTag
            $env:FRONTEND_TAG = $previous.frontendTag
            $composeArgs = @('compose', '--project-name', 'edugraph', '--env-file', $EnvFile,
                '-f', (Join-Path $PSScriptRoot 'compose.yml'))
            if ($previous.gpu) { $composeArgs += @('-f', (Join-Path $PSScriptRoot 'compose.gpu.yml')) }
            if ($previous.tunnel) { $composeArgs += @('--profile', 'tunnel') }
            Invoke-Compose @('up', '-d', '--wait', '--wait-timeout', '1200', '--remove-orphans')
            Test-Origin
        }
        throw $failure
    }
    if ($null -ne $previous) {
        $previous | ConvertTo-Json | Set-Content -LiteralPath $previousFile -Encoding utf8
    }
    $release = @{
        backendTag = $BackendTag; frontendTag = $FrontendTag
        gpu = [bool]$Gpu; tunnel = [bool]$Tunnel
        deployedAt = [DateTime]::UtcNow.ToString('o')
    }
    $tempState = "$stateFile.tmp"
    $release | ConvertTo-Json | Set-Content -LiteralPath $tempState -Encoding utf8
    Move-Item -LiteralPath $tempState -Destination $stateFile -Force
    Write-Host "Deployed backend=$BackendTag frontend=$FrontendTag; origin checks passed."
} finally {
    $env:BACKEND_TAG = $oldBackend
    $env:FRONTEND_TAG = $oldFrontend
}
