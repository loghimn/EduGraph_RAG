#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$EnvFile,
    [ValidatePattern('^sha-[0-9a-f]{40}$')][string]$BackendTag,
    [ValidatePattern('^sha-[0-9a-f]{40}$')][string]$FrontendTag,
    [string]$BackendManifest,
    [string]$FrontendManifest,
    [string]$ReleaseFile,
    [DateTimeOffset]$DeadlineUtc = [DateTimeOffset]::UtcNow.AddMinutes(55),
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
. (Join-Path $PSScriptRoot 'DeploymentProcess.ps1')
. (Join-Path $PSScriptRoot 'DeploymentHttp.ps1')
$deploymentClock = [Diagnostics.Stopwatch]::StartNew()
$totalBudgetSeconds = [Math]::Min(3300, ($DeadlineUtc - [DateTimeOffset]::UtcNow).TotalSeconds)
$recoveryReserveSeconds = 1800
$recovering = $false

function Get-DeploymentElapsedSeconds { return $deploymentClock.Elapsed.TotalSeconds }

function Get-OperationTimeout([int]$MaximumSeconds) {
    $remaining = $totalBudgetSeconds - (Get-DeploymentElapsedSeconds)
    if (-not $recovering) { $remaining -= $recoveryReserveSeconds }
    if ($remaining -lt 1) {
        $phase = if ($recovering) { 'recovery' } else { 'forward' }
        throw [TimeoutException]::new("Deployment $phase deadline exceeded.")
    }
    return [int][Math]::Floor([Math]::Min($MaximumSeconds, $remaining))
}

function Invoke-Docker([string[]]$Arguments, [switch]$CaptureOutput) {
    $maximum = if ($Arguments -contains 'pull') { if ($recovering) { 120 } else { 900 } }
        elseif ($Arguments -contains 'up') { 1205 } else { 30 }
    $timeout = Get-OperationTimeout $maximum
    if ($Arguments -contains 'up') {
        if ($timeout -le 5) { throw [TimeoutException]::new('Insufficient deployment time remaining for activation.') }
        $waitIndex = [Array]::IndexOf($Arguments, '--wait-timeout')
        if ($waitIndex -ge 0) { $Arguments[$waitIndex + 1] = [string][Math]::Min(1200, $timeout - 5) }
    }
    $dockerPath = Get-DockerExecutable
    $result = Invoke-DeploymentProcess -FilePath $dockerPath -Arguments $Arguments -TimeoutSeconds $timeout
    $script:dockerExitCode = $result.ExitCode
    if ($CaptureOutput) {
        # Rendered Compose JSON can contain secrets; never print captured output.
        if ($result.Output) { return $result.Output -split '\r?\n' | Where-Object { $_ -ne '' } }
    } else {
        if ($result.Output) { Write-Host $result.Output.TrimEnd() }
        if ($result.Error) { Write-Host $result.Error.TrimEnd() }
    }
}

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
    Invoke-Docker ($composeArgs + $Arguments)
    if ($dockerExitCode -ne 0) { throw "Docker Compose failed: $($Arguments[0])" }
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

function Read-CIManifest([string]$Path, [string]$Kind) {
    if (-not $Path) { throw 'Fresh deployments require both BackendManifest and FrontendManifest from selected successful CI builds; commit tags alone are not sufficient.' }
    $manifest = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json -AsHashtable
    $required = if ($Kind -eq 'backend') {
        @('core-service', 'gateway-service', 'edugraph-ai-core', 'edugraph-ai-core-gpu')
    } else { @('frontend') }
    if ($manifest.schemaVersion -ne 1 -or $manifest.kind -cne $Kind -or
        $manifest.repository -cnotmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$' -or
        $manifest.commit -cnotmatch '^[a-f0-9]{40}$' -or $manifest.tag -cne "sha-$($manifest.commit)" -or
        [string]$manifest.runId -cnotmatch '^[1-9][0-9]*$' -or [string]$manifest.attempt -cnotmatch '^[1-9][0-9]*$' -or
        $manifest.images -isnot [System.Collections.IDictionary] -or $manifest.images.Count -ne $required.Count) {
        throw "Invalid or incomplete $Kind CI release manifest."
    }
    foreach ($name in $required) {
        if ($manifest.images[$name] -cnotmatch '^[a-z0-9][a-z0-9._:/-]*@sha256:[a-f0-9]{64}$') {
            throw "Missing or invalid digest for $Kind component: $name"
        }
    }
    return $manifest
}

function Stop-InactiveTunnel([bool]$UseTunnel) {
    if ($UseTunnel) { return }
    # A disabled profile does not stop a connector from an earlier release.
    # Explicitly enable its profile only for these project-scoped stop/inspect commands.
    $connectorArgs = $composeArgs + @('--profile', 'tunnel')
    Invoke-Docker ($connectorArgs + @('stop', 'cloudflared'))
    if ($dockerExitCode -ne 0) { throw 'Cannot stop project Cloudflare connector.' }
    $running = @(Invoke-Docker ($connectorArgs + @('ps', '--status', 'running', '-q', 'cloudflared')) -CaptureOutput)
    if ($dockerExitCode -ne 0) { throw 'Cannot verify project Cloudflare connector state.' }
    if ($running.Count -gt 0) { throw 'Cloudflare connector is still running in tunnel-off mode.' }
}

function Get-NormalizedRepository([string]$Reference) {
    $repository = $Reference -replace '@.*$', '' -replace ':[^/:]+$', ''
    $parts = $repository.Split('/', 2)
    if ($parts.Count -eq 1 -or ($parts[0] -notmatch '[.:]' -and $parts[0] -cne 'localhost')) {
        $repository = "docker.io/$repository"
    }
    $repository = $repository -creplace '^index\.docker\.io/', 'docker.io/'
    if ($repository -cmatch '^docker\.io/[^/]+$') {
        $repository = $repository -creplace '^docker\.io/', 'docker.io/library/'
    }
    return $repository
}

function Get-PulledConnectorImage([string]$Image, [string[]]$PullOutput) {
    # A successful digest pull already identifies the exact requested manifest.
    if ($Image -cmatch '@sha256:[a-f0-9]{64}$') { return $Image }
    if ($Image.Contains('@')) { throw "Invalid configured connector digest: $Image" }
    $pullDigests = @($PullOutput | ForEach-Object {
        if ($_ -cmatch '^Digest:\s+(sha256:[a-f0-9]{64})\s*$') { $Matches[1] }
    } | Select-Object -Unique)
    if ($pullDigests.Count -ne 1) { throw "Cannot determine the digest reported by connector pull: $Image" }
    $repository = Get-NormalizedRepository $Image
    $output = Invoke-Docker @('image', 'inspect', '--format', '{{json .RepoDigests}}', $Image) -CaptureOutput
    if ($dockerExitCode -ne 0) { throw "Cannot inspect pulled image: $Image" }
    $digests = @($output | ConvertFrom-Json)
    $reference = $digests | Where-Object {
        $_.EndsWith("@$($pullDigests[0])", [StringComparison]::Ordinal) -and
            (Get-NormalizedRepository $_) -ceq $repository
    } | Select-Object -First 1
    if (-not $reference) { throw "No repository digest found for pulled image: $Image" }
    return $reference
}

function Test-RunningImages([System.Collections.IDictionary]$Images) {
    foreach ($name in $Images.Keys) {
        $containers = @(Invoke-Docker ($composeArgs + @('ps', '-q', $name)) -CaptureOutput)
        if ($dockerExitCode -ne 0 -or $containers.Count -ne 1) { throw "Expected one running container for service: $name" }
        $expected = Invoke-Docker @('image', 'inspect', '--format', '{{.Id}}', $Images[$name]) -CaptureOutput
        if ($dockerExitCode -ne 0) { throw "Cannot inspect pinned image for service: $name" }
        $actual = Invoke-Docker @('inspect', '--format', '{{.Image}}', $containers[0]) -CaptureOutput
        if ($dockerExitCode -ne 0 -or $actual -ne $expected) { throw "Running image differs from pinned digest for service: $name" }
    }
}

function Ensure-ReleaseImages([System.Collections.IDictionary]$Images) {
    foreach ($reference in $Images.Values) {
        $null = Invoke-Docker @('image', 'inspect', '--format', '{{.Id}}', $reference) -CaptureOutput
        if ($dockerExitCode -eq 0) { continue }
        Invoke-Docker @('pull', $reference)
        if ($dockerExitCode -ne 0) { throw "Cannot pull missing rollback image: $reference" }
        $null = Invoke-Docker @('image', 'inspect', '--format', '{{.Id}}', $reference) -CaptureOutput
        if ($dockerExitCode -ne 0) { throw "Rollback image is unavailable after pull: $reference" }
    }
}

function Test-Origin {
    foreach ($path in @('/healthz', '/courses')) {
        $response = Invoke-DeploymentWebRequest "http://127.0.0.1:8088$path" -TimeoutSec (Get-OperationTimeout 15)
        if ($response.StatusCode -ne 200) { throw "Origin check failed: $path" }
        if ($path -eq '/courses' -and $response.Content -notmatch '<html') {
            throw 'SPA fallback did not return HTML'
        }
        if ($path -eq '/courses') { $frontendHtml = $response.Content }
    }
    $response = Invoke-DeploymentWebRequest 'http://127.0.0.1:8088/api/users/me' -SkipHttpErrorCheck -TimeoutSec (Get-OperationTimeout 15)
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
                $response = Invoke-DeploymentWebRequest $probe -TimeoutSec (Get-OperationTimeout 10) -SkipHttpErrorCheck -MaximumRedirection 0 -Headers @{ 'Cache-Control' = 'no-cache' }
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
            Start-Sleep -Seconds (Get-OperationTimeout 2)
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
    $null = Get-OperationTimeout 1
    # Read rollback selections as well as current state only while holding the lock.
    $selectedRelease = if ($ReleaseFile) {
        Get-Content -LiteralPath $ReleaseFile -Raw | ConvertFrom-Json -AsHashtable
    } else { $null }
    if ($null -ne $selectedRelease) {
        $BackendTag = $selectedRelease.backendTag
        $FrontendTag = $selectedRelease.frontendTag
        $Gpu = [bool]$selectedRelease.gpu
        $Tunnel = [bool]$selectedRelease.tunnel
        $builds = $selectedRelease.builds
    } else {
        $backendBuild = Read-CIManifest $BackendManifest 'backend'
        $frontendBuild = Read-CIManifest $FrontendManifest 'frontend'
        if (($BackendTag -and $BackendTag -cne $backendBuild.tag) -or
            ($FrontendTag -and $FrontendTag -cne $frontendBuild.tag)) {
            throw 'Commit tags do not match the selected CI manifests.'
        }
        $BackendTag = $backendBuild.tag
        $FrontendTag = $frontendBuild.tag
        $aiComponent = if ($Gpu) { 'edugraph-ai-core-gpu' } else { 'edugraph-ai-core' }
        $manifestImages = @{
            'core-service' = $backendBuild.images['core-service']
            'gateway-service' = $backendBuild.images['gateway-service']
            'ai-core' = $backendBuild.images[$aiComponent]
            frontend = $frontendBuild.images.frontend
        }
        $builds = @{}
        foreach ($build in @($backendBuild, $frontendBuild)) {
            $builds[$build.kind] = @{
                repository = $build.repository; commit = $build.commit
                runId = $build.runId; attempt = $build.attempt
            }
        }
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
    # Keep rendered secrets in memory only; do not print this configuration.
    $rendered = Invoke-Docker ($composeArgs + @('config', '--format', 'json')) -CaptureOutput
    if ($dockerExitCode -ne 0) { throw 'Cannot read Compose configuration.' }
    $configuration = $rendered | ConvertFrom-Json
    if ($null -eq $selectedRelease) {
        foreach ($name in $manifestImages.Keys) {
            $repository = $configuration.services.$name.image -replace '@.*$', '' -replace ':[^/:]+$', ''
            if (-not $manifestImages[$name].StartsWith("$repository@sha256:", [StringComparison]::Ordinal)) {
                throw "CI manifest image repository does not match deployment configuration: $name"
            }
        }
    }
    if ($ValidateOnly) { Write-Host 'Compose configuration is valid.'; return }
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
    $previous = $null
    if (Test-Path -LiteralPath $stateFile) {
        try {
            $previous = $currentRecord | ConvertFrom-Json -AsHashtable
            if ($previous -isnot [System.Collections.IDictionary] -or
                $previous.backendTag -cnotmatch '^sha-[0-9a-f]{40}$' -or
                $previous.frontendTag -cnotmatch '^sha-[0-9a-f]{40}$' -or
                $previous.gpu -isnot [bool] -or $previous.tunnel -isnot [bool]) {
                throw 'Expected commit tags, Boolean deployment modes and image digests.'
            }
        } catch { throw "Cannot use current release record at ${stateFile}: $($_.Exception.Message)" }
    } else {
        # Search all project containers, including stopped services and disabled profiles.
        # Moving the secrets file must never turn an existing project into a first deploy.
        $projectContainers = @(Invoke-Docker @('ps', '-a', '-q', '--filter', 'label=com.docker.compose.project=edugraph') -CaptureOutput)
        if ($dockerExitCode -ne 0) { throw 'Cannot determine whether the edugraph project is empty.' }
        if ($projectContainers.Count -gt 0) {
            throw "Existing edugraph containers have no current release record at ${stateFile}. Restore the matching release metadata before deploying."
        }
    }
    $previousOverride = if ($null -ne $previous) {
        New-ImageOverride $previous.images ([bool]$previous.tunnel)
    } else { $null }

    $images = if ($null -ne $selectedRelease) { $selectedRelease.images } else { $manifestImages }
    if ($null -eq $selectedRelease) {
        if ($Tunnel) {
            # Third-party connector is outside the application CI build set.
            $connectorPull = @(Invoke-Docker @('pull', $configuration.services.cloudflared.image) -CaptureOutput)
            if ($dockerExitCode -ne 0) { throw 'Docker Compose failed: pull' }
            $images.cloudflared = Get-PulledConnectorImage $configuration.services.cloudflared.image $connectorPull
        }
        $composeArgs += @('-f', (New-ImageOverride $images $Tunnel))
    }
    # Application images are pulled only by the complete selected build's digests.
    if ($null -ne $selectedRelease) { Ensure-ReleaseImages $images }
    else { Invoke-Compose @('pull') }
    $release = @{
        backendTag = $BackendTag; frontendTag = $FrontendTag
        gpu = [bool]$Gpu; tunnel = [bool]$Tunnel
        images = $images
        builds = $builds
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
        $null = Get-OperationTimeout 1
        if ($null -ne $previous) {
            Publish-ReleaseMetadata $tempPrevious $previousFile
            $previousPublished = $true
        }
        $null = Get-OperationTimeout 1
        # Commit current last: a failed atomic rename leaves the old record intact.
        Publish-ReleaseMetadata $tempState $stateFile
    } catch {
        $failure = $_
        $recovering = $true
        if ($null -ne $previous) {
            Write-Warning 'Deployment failed. Restoring previous image versions and deployment mode.' -WarningAction Continue
            try {
                $env:BACKEND_TAG = $previous.backendTag
                $env:FRONTEND_TAG = $previous.frontendTag
                $composeArgs = @(Get-ComposeArguments ([bool]$previous.gpu) ([bool]$previous.tunnel))
                $composeArgs += @('-f', $previousOverride)
                Ensure-ReleaseImages $previous.images
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
            # The project was confirmed empty before activation. Remove only this
            # attempt's service containers, keeping all volumes for the next retry.
            try {
                Invoke-Compose (@('stop') + @($images.Keys))
                Stop-InactiveTunnel $false
                foreach ($name in $images.Keys) {
                    $created = @(Invoke-Docker @('ps', '-a', '-q', '--filter', 'label=com.docker.compose.project=edugraph',
                        '--filter', "label=com.docker.compose.service=$name", '--filter', 'label=com.docker.compose.oneoff=False') -CaptureOutput)
                    if ($dockerExitCode -ne 0) { throw "Cannot find failed first-release containers for service: $name" }
                    if ($created.Count -gt 0) {
                        Invoke-Docker (@('rm', '--force') + $created)
                        if ($dockerExitCode -ne 0) { throw "Cannot remove failed first-release containers for service: $name" }
                    }
                }
                $remaining = @(Invoke-Docker @('ps', '-a', '-q', '--filter', 'label=com.docker.compose.project=edugraph') -CaptureOutput)
                if ($dockerExitCode -ne 0 -or $remaining.Count -gt 0) { throw 'Cannot confirm the failed first-release project is empty.' }
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
            foreach ($path in ($overrideFiles.ToArray() + $metadataStages.ToArray())) {
                try {
                    if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force -ErrorAction Stop }
                } catch {
                    Write-Warning "Cannot remove deployment temporary file '${path}': $($_.Exception.Message)" -WarningAction Continue
                }
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
