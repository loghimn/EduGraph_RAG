#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$EnvFile,
    [ValidatePattern('^sha-[0-9a-f]{40}$')][string]$BackendTag,
    [ValidatePattern('^sha-[0-9a-f]{40}$')][string]$FrontendTag,
    [string]$ReleaseFile,
    [switch]$Gpu,
    [switch]$Tunnel,
    [switch]$ValidateOnly
)
$ErrorActionPreference = 'Stop'
$EnvFile = (Resolve-Path -LiteralPath $EnvFile).Path
$stateFile = Join-Path (Split-Path $EnvFile -Parent) 'current-release.json'
$previousFile = Join-Path (Split-Path $EnvFile -Parent) 'previous-release.json'
$overrideFiles = [Collections.Generic.List[string]]::new()
$metadataStages = [Collections.Generic.List[string]]::new()
$preserveOverrideFiles = $false

function Get-ComposeArguments([bool]$UseGpu, [bool]$UseTunnel) {
    $arguments = @('compose', '--project-name', 'edugraph', '--env-file', $EnvFile,
        '-f', (Join-Path $PSScriptRoot 'compose.yml'))
    if ($UseGpu) { $arguments += @('-f', (Join-Path $PSScriptRoot 'compose.gpu.yml')) }
    if ($UseTunnel) { $arguments += @('--profile', 'tunnel') }
    return $arguments
}

function New-ImageOverride([System.Collections.IDictionary]$Images, [bool]$UseTunnel) {
    $requiredServices = @('ai-core', 'core-service', 'gateway-service', 'frontend')
    if ($UseTunnel) { $requiredServices += 'cloudflared' }
    if ($null -eq $Images -or $Images.Count -ne $requiredServices.Count) {
        throw 'Release must contain immutable image references for every active service.'
    }
    $services = @{}
    foreach ($name in $requiredServices) {
        if ($Images[$name] -notmatch '^[a-zA-Z0-9][a-zA-Z0-9._:/-]*@sha256:[a-f0-9]{64}$') {
            throw "Missing or invalid image digest for service: $name"
        }
        $services[$name] = @{ image = $Images[$name] }
    }
    # Only image references are written here, never the rendered environment/secrets.
    $path = (New-TemporaryFile).FullName
    $overrideFiles.Add($path)
    @{ services = $services } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $path -Encoding utf8
    return $path
}

function Invoke-Compose([string[]]$Arguments) {
    & docker @composeArgs @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose failed: $($Arguments[0])" }
}

function New-MetadataStage([string]$Destination, [string]$Content) {
    # Same directory/filesystem as the destination, so publication is a rename.
    $path = "$Destination.$([guid]::NewGuid().ToString('N')).tmp"
    $metadataStages.Add($path)
    [IO.File]::WriteAllText($path, $Content, [Text.UTF8Encoding]::new($false))
    return $path
}

function Publish-ReleaseMetadata([string]$Stage, [string]$Destination) {
    [IO.File]::Move($Stage, $Destination, $true)
}

function Stop-InactiveTunnel([bool]$UseTunnel) {
    if ($UseTunnel) { return }
    # A disabled profile does not stop a connector from an earlier release.
    # Explicitly enable its profile only for these project-scoped stop/inspect commands.
    $connectorArgs = $composeArgs + @('--profile', 'tunnel')
    & docker @connectorArgs stop cloudflared
    if ($LASTEXITCODE -ne 0) { throw 'Cannot stop project Cloudflare connector.' }
    $running = @(& docker @connectorArgs ps --status running -q cloudflared)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot verify project Cloudflare connector state.' }
    if ($running.Count -gt 0) { throw 'Cloudflare connector is still running in tunnel-off mode.' }
}

function Get-PulledImages($Configuration, [bool]$UseTunnel) {
    $images = @{}
    foreach ($service in $Configuration.services.PSObject.Properties) {
        if ($service.Name -eq 'cloudflared' -and -not $UseTunnel) { continue }
        $image = [string]$service.Value.image
        $repository = $image -replace '@.*$', '' -replace ':[^/:]+$', ''
        $output = & docker image inspect --format '{{json .RepoDigests}}' $image
        if ($LASTEXITCODE -ne 0) { throw "Cannot inspect pulled image: $image" }
        $digests = @($output | ConvertFrom-Json)
        $reference = $digests | Where-Object { $_.StartsWith("$repository@sha256:", [StringComparison]::Ordinal) } | Select-Object -First 1
        if (-not $reference) { throw "No repository digest found for pulled image: $image" }
        $images[$service.Name] = $reference
    }
    return $images
}

function Test-RunningImages([System.Collections.IDictionary]$Images) {
    foreach ($name in $Images.Keys) {
        $containers = @(& docker @composeArgs ps -q $name)
        if ($LASTEXITCODE -ne 0 -or $containers.Count -ne 1) { throw "Expected one running container for service: $name" }
        $expected = & docker image inspect --format '{{.Id}}' $Images[$name]
        if ($LASTEXITCODE -ne 0) { throw "Cannot inspect pinned image for service: $name" }
        $actual = & docker inspect --format '{{.Image}}' $containers[0]
        if ($LASTEXITCODE -ne 0 -or $actual -ne $expected) { throw "Running image differs from pinned digest for service: $name" }
    }
}

function Test-Origin {
    foreach ($path in @('/healthz', '/courses')) {
        $response = Invoke-WebRequest "http://127.0.0.1:8088$path" -TimeoutSec 15
        if ($response.StatusCode -ne 200) { throw "Origin check failed: $path" }
        if ($path -eq '/courses' -and $response.Content -notmatch '<html') {
            throw 'SPA fallback did not return HTML'
        }
        if ($path -eq '/courses') { $frontendHtml = $response.Content }
    }
    $response = Invoke-WebRequest 'http://127.0.0.1:8088/api/users/me' -SkipHttpErrorCheck -TimeoutSec 15
    if ($response.StatusCode -ne 401) { throw 'Protected gateway API did not return 401' }
    $assets = @([regex]::Matches($frontendHtml, '\bsrc="(/assets/[^\"]+\.js)"') | ForEach-Object { $_.Groups[1].Value })
    if ($assets.Count -eq 0) { throw 'SPA response did not contain a frontend JavaScript asset.' }
    return $assets
}

function Test-PublicRoute([bool]$UseTunnel, [string[]]$FrontendAssets) {
    if (-not $UseTunnel) { return }
    $publicOrigin = 'https://edugraph-rag.io.vn'
    # Finite retries let a newly connected tunnel settle without waiting indefinitely.
    for ($attempt = 1; $attempt -le 4; $attempt++) {
        try {
            foreach ($path in @('/healthz', '/courses', '/api/users/me')) {
                $probe = "$publicOrigin${path}?deploy-check=$([guid]::NewGuid().ToString('N'))"
                $response = Invoke-WebRequest $probe -TimeoutSec 10 -SkipHttpErrorCheck -MaximumRedirection 0 -Headers @{ 'Cache-Control' = 'no-cache' }
                $expectedStatus = if ($path -eq '/api/users/me') { 401 } else { 200 }
                if ($response.StatusCode -ne $expectedStatus) { throw "Unexpected public status for $path" }
                if ($path -eq '/healthz' -and $response.Content.Trim() -ne 'ok') { throw 'Unexpected public health response.' }
                if ($path -eq '/courses') {
                    if ($response.Content -notmatch '<html') { throw 'Public SPA fallback did not return HTML.' }
                    foreach ($asset in $FrontendAssets) {
                        if (-not $response.Content.Contains($asset)) { throw 'Public route did not return the deployed frontend assets.' }
                    }
                }
            }
            return
        } catch {
            if ($attempt -eq 4) { throw "Public route check failed for $publicOrigin after 4 attempts: $($_.Exception.Message)" }
            Start-Sleep -Seconds 2
        }
    }
}

$oldBackend = $env:BACKEND_TAG
$oldFrontend = $env:FRONTEND_TAG
$deploymentLock = $null
$lockHeld = $false
try {
    # Same project/group as workflow concurrency, shared across checkouts, accounts
    # with mutex access, and Windows sessions (workflow runner and direct pwsh).
    $deploymentLock = [Threading.Mutex]::new($false, 'Global\edugraph-production')
    try {
        $lockHeld = $deploymentLock.WaitOne(0)
    } catch [Threading.AbandonedMutexException] {
        # WaitOne grants ownership when a previous process terminated unexpectedly.
        $lockHeld = $true
        Write-Warning 'Previous deployment terminated while holding the project lock; inspect the last release and container state.'
    }
    if (-not $lockHeld) {
        throw 'Another edugraph deployment is already running on this host. Wait for it to finish, then retry.'
    }
    # Read rollback selections as well as current state only while holding the lock.
    $selectedRelease = if ($ReleaseFile) {
        Get-Content -LiteralPath $ReleaseFile -Raw | ConvertFrom-Json -AsHashtable
    } else { $null }
    if ($null -ne $selectedRelease) {
        $BackendTag = $selectedRelease.backendTag
        $FrontendTag = $selectedRelease.frontendTag
        $Gpu = [bool]$selectedRelease.gpu
        $Tunnel = [bool]$selectedRelease.tunnel
    }
    if ($BackendTag -notmatch '^sha-[0-9a-f]{40}$' -or $FrontendTag -notmatch '^sha-[0-9a-f]{40}$') {
        throw 'Provide both commit tags, or a release file containing valid tags and image digests.'
    }
    $composeArgs = @(Get-ComposeArguments $Gpu $Tunnel)
    $env:BACKEND_TAG = $BackendTag
    $env:FRONTEND_TAG = $FrontendTag
    if ($null -ne $selectedRelease) {
        $composeArgs += @('-f', (New-ImageOverride $selectedRelease.images $Tunnel))
    }
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
    $currentRecord = if (Test-Path -LiteralPath $stateFile) { Get-Content -LiteralPath $stateFile -Raw } else { $null }
    $previous = if ($null -ne $currentRecord) { $currentRecord | ConvertFrom-Json -AsHashtable } else { $null }
    $previousOverride = if ($null -ne $previous) {
        New-ImageOverride $previous.images ([bool]$previous.tunnel)
    } else { $null }

    # Pull all images successfully before replacing any running service.
    Invoke-Compose @('pull')
    $images = if ($null -ne $selectedRelease) { $selectedRelease.images } else { Get-PulledImages $configuration $Tunnel }
    if ($null -eq $selectedRelease) {
        $composeArgs += @('-f', (New-ImageOverride $images $Tunnel))
    }
    $release = @{
        backendTag = $BackendTag; frontendTag = $FrontendTag
        gpu = [bool]$Gpu; tunnel = [bool]$Tunnel
        images = $images
        deployedAt = [DateTime]::UtcNow.ToString('o')
    }
    # Prepare every write, including recovery of previous-release.json, before up.
    $tempState = New-MetadataStage $stateFile ($release | ConvertTo-Json -Depth 4)
    $previousExisted = Test-Path -LiteralPath $previousFile
    $previousBackup = if ($previousExisted) {
        New-MetadataStage $previousFile (Get-Content -LiteralPath $previousFile -Raw)
    } else { $null }
    $tempPrevious = if ($null -ne $previous) { New-MetadataStage $previousFile $currentRecord } else { $null }
    $previousPublished = $false
    try {
        Invoke-Compose @('up', '-d', '--pull', 'never', '--wait', '--wait-timeout', '1200', '--remove-orphans')
        Stop-InactiveTunnel $Tunnel
        $frontendAssets = @(Test-Origin)
        Test-RunningImages $images
        Test-PublicRoute $Tunnel $frontendAssets
        if ($null -ne $previous) {
            Publish-ReleaseMetadata $tempPrevious $previousFile
            $previousPublished = $true
        }
        # Commit current last: a failed atomic rename leaves the old record intact.
        Publish-ReleaseMetadata $tempState $stateFile
    } catch {
        $failure = $_
        if ($null -ne $previous) {
            Write-Warning 'Deployment failed. Restoring previous image versions and deployment mode.' -WarningAction Continue
            try {
                $env:BACKEND_TAG = $previous.backendTag
                $env:FRONTEND_TAG = $previous.frontendTag
                $composeArgs = @(Get-ComposeArguments ([bool]$previous.gpu) ([bool]$previous.tunnel))
                $composeArgs += @('-f', $previousOverride)
                Invoke-Compose @('pull')
                Invoke-Compose @('up', '-d', '--pull', 'never', '--wait', '--wait-timeout', '1200', '--remove-orphans')
                Stop-InactiveTunnel ([bool]$previous.tunnel)
                $frontendAssets = @(Test-Origin)
                Test-RunningImages $previous.images
                Test-PublicRoute ([bool]$previous.tunnel) $frontendAssets
            } catch {
                $preserveOverrideFiles = $true
                $failure.Exception.Data['RollbackFailure'] = $_.Exception.Message
                Write-Warning "Runtime/state reconciliation failed; restoration could not be confirmed: $($_.Exception.Message)" -WarningAction Continue
            }
        } else {
            # A first release has no recorded runtime to restore; stop its services.
            try {
                Invoke-Compose (@('stop') + @($images.Keys))
                Stop-InactiveTunnel $false
                $remaining = @(& docker @composeArgs --profile tunnel ps --status running -q)
                if ($LASTEXITCODE -ne 0 -or $remaining.Count -gt 0) { throw 'Cannot confirm the first release was stopped.' }
            } catch {
                $preserveOverrideFiles = $true
                $failure.Exception.Data['RollbackFailure'] = $_.Exception.Message
                Write-Warning "Runtime/state reconciliation failed; restoration could not be confirmed: $($_.Exception.Message)" -WarningAction Continue
            }
        }
        # Restore metadata even if restoring the runtime failed.
        if ($previousPublished) {
            try {
                if ($previousExisted) { Publish-ReleaseMetadata $previousBackup $previousFile }
                else { Remove-Item -LiteralPath $previousFile -Force }
            } catch {
                $preserveOverrideFiles = $true
                $failure.Exception.Data['StateRecoveryFailure'] = $_.Exception.Message
                Write-Warning "Release-state reconciliation failed; manual recovery is required: $($_.Exception.Message)" -WarningAction Continue
            }
        }
        if ($preserveOverrideFiles) {
            $retainedMetadata = @($metadataStages | Where-Object { Test-Path -LiteralPath $_ })
            Write-Warning "Release records: $stateFile; $previousFile. Recovery image overrides: $($overrideFiles -join ', '). Staged metadata: $($retainedMetadata -join ', ')" -WarningAction Continue
        }
        throw $failure
    }
    Write-Host "Deployed backend=$BackendTag frontend=$FrontendTag; required origin/public checks passed."
} finally {
    try {
        $env:BACKEND_TAG = $oldBackend
        $env:FRONTEND_TAG = $oldFrontend
        if (-not $preserveOverrideFiles) {
            foreach ($path in $overrideFiles) { Remove-Item -LiteralPath $path -Force }
            foreach ($path in $metadataStages) {
                if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force }
            }
        }
    } finally {
        if ($null -ne $deploymentLock) {
            try {
                if ($lockHeld) { $deploymentLock.ReleaseMutex() }
            } finally { $deploymentLock.Dispose() }
        }
    }
}
