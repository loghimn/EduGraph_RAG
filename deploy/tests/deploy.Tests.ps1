#requires -Version 7.0
# Dependency-free orchestration tests. No Docker daemon, secrets or network used.
$ErrorActionPreference = 'Stop'
$deployScript = Join-Path $PSScriptRoot '../deploy.ps1'
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ("edugraph-deploy-tests-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $testRoot | Out-Null
$testEnv = Join-Path $testRoot 'production.env'
Set-Content -LiteralPath $testEnv -Value '# isolated deployment test'
$global:edugraphTestCalls = [Collections.Generic.List[object]]::new()
$global:edugraphTestFailNextUp = $false
$global:edugraphTestRemainingUpFailures = 0
$global:edugraphTestFailPull = $false
$global:edugraphTestRecoveryRegistryDown = $false
$global:edugraphTestMissingImages = [Collections.Generic.HashSet[string]]::new()
$global:edugraphTestBadOrigin = $false
$global:edugraphTestTagGeneration = 'original'
$global:edugraphTestRunningImages = @{}
$global:edugraphTestStoppedProjectContainer = $false
$global:edugraphTestFailProjectQuery = $false
$global:edugraphTestNoDigest = $false
$global:edugraphTestConnectorImage = $null
$global:edugraphTestConnectorDigestRepository = $null
$global:edugraphTestWrongImage = $false
$global:edugraphTestFailNextStop = $false
$global:edugraphTestIgnoreNextStop = $false
$global:edugraphTestCheckLockAtOrigin = $true
$global:edugraphTestLockCheckCount = 0
$global:edugraphTestHttpCalls = [Collections.Generic.List[object]]::new()
$global:edugraphTestPublicFailures = 0
$global:edugraphTestPublicBodyTimeouts = 0
$global:edugraphTestPublicWrongAssets = 0
$global:edugraphTestPublicStatus = 503
$global:edugraphTestRetryDelays = 0
$preservedOverrides = [Collections.Generic.HashSet[string]]::new()
$global:edugraphTestFailMetadataStage = $false
$global:edugraphTestPublicationFailures = [Collections.Generic.Queue[string]]::new()
$global:edugraphTestFailRollbackOnPublish = $false
$global:edugraphTestCurrentStage = $null
$global:edugraphTestCheckStageAtUp = $false
$global:edugraphTestProcessCalls = [Collections.Generic.List[object]]::new()
$global:edugraphTestElapsedSeconds = 0
$global:edugraphTestAdvanceAfterPull = 0
$global:edugraphTestTimeoutActivation = $false
$global:edugraphTestTimeoutForwardPull = $false
$global:edugraphTestTimeoutRecoveryPull = $false
$global:edugraphTestAdvanceAtOrigin = 0

function Invoke-TestDeploymentElapsedSeconds { return $global:edugraphTestElapsedSeconds }

function Invoke-TestDockerProcess([string]$FilePath, [string[]]$Arguments, [int]$TimeoutSeconds) {
    $global:edugraphTestProcessCalls.Add(@{ arguments = $Arguments; timeout = $TimeoutSeconds; recovering = [bool]$recovering })
    $output = @(& docker @Arguments) -join [Environment]::NewLine
    if ($Arguments -contains 'pull' -and -not $recovering -and $global:edugraphTestAdvanceAfterPull -gt 0) {
        $global:edugraphTestElapsedSeconds += $global:edugraphTestAdvanceAfterPull
        $global:edugraphTestAdvanceAfterPull = 0
    }
    $timeout = ($Arguments -contains 'up' -and -not $recovering -and $global:edugraphTestTimeoutActivation) -or
        ($Arguments -contains 'pull' -and -not $recovering -and $global:edugraphTestTimeoutForwardPull) -or
        ($Arguments -contains 'pull' -and $recovering -and $global:edugraphTestTimeoutRecoveryPull)
    if ($timeout) {
        if ($Arguments -contains 'up') { $global:edugraphTestTimeoutActivation = $false }
        if ($Arguments -contains 'pull' -and $recovering) { $global:edugraphTestTimeoutRecoveryPull = $false }
        if ($Arguments -contains 'pull' -and -not $recovering) { $global:edugraphTestTimeoutForwardPull = $false }
        $global:edugraphTestElapsedSeconds += $TimeoutSeconds
        throw [TimeoutException]::new('Simulated Docker command budget exceeded')
    }
    return @{ ExitCode = $global:LASTEXITCODE; Output = $output; Error = '' }
}

function Get-TestImageId([string]$Reference) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($Reference)
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        $hash = $sha256.ComputeHash($bytes)
        return 'sha256:' + [BitConverter]::ToString($hash).Replace('-', '').ToLowerInvariant()
    } finally { $sha256.Dispose() }
}

function docker {
    $images = @{}
    for ($index = 0; $index -lt $args.Count - 1; $index++) {
        if ($args[$index] -eq '-f' -and [IO.Path]::GetExtension($args[$index + 1]) -eq '.tmp') {
            $override = Get-Content -LiteralPath $args[$index + 1] -Raw | ConvertFrom-Json -AsHashtable
            foreach ($name in $override.services.Keys) { $images[$name] = $override.services[$name].image }
        }
    }
    $global:edugraphTestCalls.Add(@{ arguments = @($args); backend = $env:BACKEND_TAG; frontend = $env:FRONTEND_TAG; images = $images })
    $global:LASTEXITCODE = 0
    if ($args[0] -eq 'ps' -and $args -contains 'label=com.docker.compose.project=edugraph') {
        Assert-True ($args -contains '-a') 'missing-state protection must include stopped containers'
        if ($global:edugraphTestFailProjectQuery) { $global:LASTEXITCODE = 1; return }
        if ($global:edugraphTestStoppedProjectContainer) { 'stopped-project-container' }
        foreach ($name in $global:edugraphTestRunningImages.Keys) { "container-$name" }
        return
    }
    if ($args[0] -eq 'image') {
        $reference = [string]$args[-1]
        if ($args -contains '{{json .RepoDigests}}') {
            if ($global:edugraphTestNoDigest) { return '[]' }
            $repository = $reference -replace ':[^/:]+$', ''
            if ($global:edugraphTestConnectorImage -eq $reference -and $global:edugraphTestConnectorDigestRepository) {
                $repository = $global:edugraphTestConnectorDigestRepository
            }
            $digest = Get-TestImageId "$reference-$global:edugraphTestTagGeneration"
            return ConvertTo-Json -InputObject @("$repository@$digest") -Compress
        }
        if ($global:edugraphTestMissingImages.Contains($reference)) { $global:LASTEXITCODE = 1; return }
        return Get-TestImageId $reference
    }
    if ($args[0] -eq 'inspect') {
        if ($global:edugraphTestWrongImage) {
            $global:edugraphTestWrongImage = $false
            return 'sha256:wrong-image'
        }
        $name = $args[-1] -replace '^container-', ''
        return Get-TestImageId $global:edugraphTestRunningImages[$name]
    }
    if ($args -contains 'stop') {
        if ($global:edugraphTestFailNextStop) {
            $global:edugraphTestFailNextStop = $false
            $global:LASTEXITCODE = 1
            return
        }
        if ($global:edugraphTestIgnoreNextStop) {
            $global:edugraphTestIgnoreNextStop = $false
            return
        }
        $stopIndex = [Array]::IndexOf($args, 'stop')
        foreach ($name in $args[($stopIndex + 1)..($args.Count - 1)]) { $global:edugraphTestRunningImages.Remove([string]$name) }
        return
    }
    if ($args -contains 'ps') {
        if ($args[-1] -eq '-q') {
            foreach ($name in $global:edugraphTestRunningImages.Keys) { "container-$name" }
            return
        }
        if ($global:edugraphTestRunningImages.ContainsKey([string]$args[-1])) { return "container-$($args[-1])" }
        return
    }
    if ($args -contains 'config' -and $args -contains '--format') {
        $configuration = @{ services = @{} }
        foreach ($name in @('ai-core', 'core-service', 'gateway-service', 'frontend', 'cloudflared')) {
            $tag = if ($name -eq 'frontend') { $env:FRONTEND_TAG } else { $env:BACKEND_TAG }
            $variant = if ($name -eq 'ai-core' -and ($args -join ' ') -match 'compose.gpu.yml') { '-gpu' } else { '' }
            $configuration.services[$name] = @{ image = "ghcr.io/test/$name${variant}:$tag"; environment = @{} }
            if ($images.ContainsKey($name)) { $configuration.services[$name].image = $images[$name] }
        }
        $configuration.services['core-service'].environment.JWT_SECRET = 'dGVzdC1vbmx5LWp3dC1zZWNyZXQtdGhhdC1pcy1hdC1sZWFzdC0zMi1ieXRlcw=='
        $configuration.services.cloudflared.environment.TUNNEL_TOKEN = 'test-token'
        if ($global:edugraphTestConnectorImage -and -not $images.ContainsKey('cloudflared')) {
            $configuration.services.cloudflared.image = $global:edugraphTestConnectorImage
        }
        return $configuration | ConvertTo-Json -Depth 5
    }
    if ($global:edugraphTestFailPull -and $args -contains 'pull') { $global:LASTEXITCODE = 1 }
    if ($args -contains 'pull' -and $recovering -and $global:edugraphTestRecoveryRegistryDown) { $global:LASTEXITCODE = 1 }
    if ($args[0] -eq 'pull' -and $global:LASTEXITCODE -eq 0) { $null = $global:edugraphTestMissingImages.Remove([string]$args[-1]) }
    if ($args -contains 'up' -and $global:edugraphTestCheckStageAtUp) {
        $global:edugraphTestCheckStageAtUp = $false
        Assert-True (Test-Path -LiteralPath $global:edugraphTestCurrentStage) 'new metadata must be staged before any container replacement'
        $staged = Get-Content -LiteralPath $global:edugraphTestCurrentStage -Raw | ConvertFrom-Json
        Assert-True ($staged.backendTag -eq $env:BACKEND_TAG) 'staged metadata must describe the target release before up'
    }
    if (($global:edugraphTestFailNextUp -or $global:edugraphTestRemainingUpFailures -gt 0) -and $args -contains 'up') {
        $global:edugraphTestFailNextUp = $false
        if ($global:edugraphTestRemainingUpFailures -gt 0) { $global:edugraphTestRemainingUpFailures-- }
        # up may fail after starting services, including a newly enabled connector.
        $global:edugraphTestRunningImages = $images.Clone()
        $global:LASTEXITCODE = 1
        return
    }
    if ($args -contains 'up') {
        $connector = $global:edugraphTestRunningImages['cloudflared']
        $global:edugraphTestRunningImages = $images.Clone()
        # Model Compose retaining a connector whose profile is now disabled.
        if ($connector -and -not $images.ContainsKey('cloudflared')) {
            $global:edugraphTestRunningImages['cloudflared'] = $connector
        }
    }
}
function Invoke-TestWebRequest {
    param([string]$Uri, [int]$TimeoutSec, [switch]$SkipHttpErrorCheck, [int]$MaximumRedirection, [hashtable]$Headers)
    $url = [uri]$Uri
    $path = $url.AbsolutePath
    $public = $url.Scheme -eq 'https'
    $global:edugraphTestHttpCalls.Add(@{
        uri = $Uri; public = $public; path = $path; timeout = $TimeoutSec
        redirects = $MaximumRedirection; redirectsSpecified = $PSBoundParameters.ContainsKey('MaximumRedirection')
        headers = $Headers
    })
    if ($public -and $path -eq '/courses' -and $global:edugraphTestPublicBodyTimeouts -gt 0) {
        $global:edugraphTestPublicBodyTimeouts--
        throw [TimeoutException]::new('Smoke request exceeded its full-response deadline.')
    }
    if (-not $public -and $path -eq '/healthz' -and $global:edugraphTestAdvanceAtOrigin -gt 0) {
        $global:edugraphTestElapsedSeconds = $global:edugraphTestAdvanceAtOrigin
        $global:edugraphTestAdvanceAtOrigin = 0
    }
    if ($public -and $global:edugraphTestPublicFailures -gt 0) {
        $global:edugraphTestPublicFailures--
        return @{ StatusCode = $global:edugraphTestPublicStatus; Content = '<html>Wrong upstream or unavailable tunnel</html>' }
    }
    if ($public -and $path -eq '/courses' -and $global:edugraphTestPublicWrongAssets -gt 0) {
        $global:edugraphTestPublicWrongAssets--
        return @{ StatusCode = 200; Content = '<html><script src="/assets/wrong-frontend.js"></script></html>' }
    }
    if ($global:edugraphTestBadOrigin) { throw 'Simulated origin failure' }
    if ($Uri.EndsWith('/api/users/me') -and $global:edugraphTestCheckLockAtOrigin) {
        $global:edugraphTestCheckLockAtOrigin = $false
        $global:edugraphTestLockCheckCount++
        $marker = Join-Path $testRoot "origin-lock-probe-$global:edugraphTestLockCheckCount.txt"
        $result = Invoke-DeploymentLockProbe $testEnv $marker
        Assert-True ($result -like 'Another edugraph deployment is already running*') 'the project lock must remain held during deployment and rollback health checks'
        Assert-True (-not (Test-Path -LiteralPath $marker)) 'concurrent validation must not reach Docker during health checks'
    }
    @{ StatusCode = $(if ($path -eq '/api/users/me') { 401 } else { 200 }); Content = $(if ($path -eq '/healthz') { 'ok' } else { '<html><script src="/assets/index-test.js"></script></html>' }) }
}
function Invoke-TestRetryDelay {
    param([int]$Seconds)
    Assert-True ($Seconds -eq 2) 'public route retries must use the bounded delay'
    $global:edugraphTestRetryDelays++
}
function Assert-True($Condition, [string]$Message) {
    if (-not $Condition) { throw "Assertion failed: $Message" }
}

function New-TestCIManifests([string]$BackendTag, [string]$FrontendTag) {
    $backendImages = @{}
    foreach ($component in @('core-service', 'gateway-service', 'edugraph-ai-core', 'edugraph-ai-core-gpu')) {
        $name = $component -replace '^edugraph-ai-core', 'ai-core'
        $source = "ghcr.io/test/${name}:$BackendTag"
        $backendImages[$component] = "ghcr.io/test/$name@$(Get-TestImageId "$source-$global:edugraphTestTagGeneration")"
    }
    $source = "ghcr.io/test/frontend:$FrontendTag"
    $manifests = @{}
    foreach ($kind in @('backend', 'frontend')) {
        $tag = if ($kind -eq 'backend') { $BackendTag } else { $FrontendTag }
        $images = if ($kind -eq 'backend') { $backendImages } else {
            @{ frontend = "ghcr.io/test/frontend@$(Get-TestImageId "$source-$global:edugraphTestTagGeneration")" }
        }
        $manifest = @{
            schemaVersion = 1; kind = $kind; repository = "test/$kind"; tag = $tag
            commit = $tag.Substring(4); runId = '123'; attempt = '1'; images = $images
        }
        $path = Join-Path $testRoot "$kind-$([guid]::NewGuid().ToString('N')).json"
        $manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $path
        $manifests[$kind] = $path
    }
    return $manifests
}

function Invoke-TestDeployment {
    [CmdletBinding()]
    param([string]$EnvFile, [string]$BackendTag, [string]$FrontendTag, [string]$ReleaseFile, [DateTimeOffset]$DeadlineUtc,
          [switch]$Gpu, [switch]$Tunnel, [switch]$ValidateOnly)
    $parameters = @{} + $PSBoundParameters
    if (-not $ReleaseFile) {
        $manifests = New-TestCIManifests $BackendTag $FrontendTag
        $parameters.BackendManifest = $manifests.backend
        $parameters.FrontendManifest = $manifests.frontend
    }
    & $deployScript @parameters
}

function Assert-RecoveryOverrides($Calls, [int]$MinimumCount = 2) {
    $paths = @($Calls.arguments | Where-Object { [IO.Path]::GetExtension([string]$_) -eq '.tmp' } | Select-Object -Unique)
    Assert-True ($paths.Count -ge $MinimumCount) 'image overrides used before recovery failure must remain available'
    foreach ($path in $paths) {
        Assert-True (Test-Path -LiteralPath $path) 'failed rollback must preserve its image override files'
        [void]$preservedOverrides.Add($path)
        $content = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json -AsHashtable
        Assert-True ($content.Keys.Count -eq 1 -and $content.ContainsKey('services')) 'recovery override must contain only image configuration'
    }
}

function Invoke-TestMetadataStage([string]$Destination, [string]$Content) {
    if ($global:edugraphTestFailMetadataStage) {
        $global:edugraphTestFailMetadataStage = $false
        throw 'Simulated metadata staging failure'
    }
    $path = & (Get-Command New-MetadataStage -CommandType Function) $Destination $Content
    if ((Split-Path $Destination -Leaf) -eq 'current-release.json') {
        $global:edugraphTestCurrentStage = $path
        $global:edugraphTestCheckStageAtUp = $true
    }
    return $path
}

function Invoke-TestMetadataPublish([string]$Stage, [string]$Destination) {
    $name = Split-Path $Destination -Leaf
    if ($global:edugraphTestPublicationFailures.Count -gt 0 -and $global:edugraphTestPublicationFailures.Peek() -eq $name) {
        [void]$global:edugraphTestPublicationFailures.Dequeue()
        if ($global:edugraphTestFailRollbackOnPublish) {
            $global:edugraphTestFailRollbackOnPublish = $false
            $global:edugraphTestFailNextUp = $true
        }
        throw "Simulated publication failure: $name"
    }
    [IO.File]::Move($Stage, $Destination, $true)
}

function Assert-TestRuntime([System.Collections.IDictionary]$Images) {
    Assert-True ($global:edugraphTestRunningImages.Count -eq $Images.Count) 'runtime must restore exactly the previous active services'
    foreach ($name in $Images.Keys) {
        Assert-True ($global:edugraphTestRunningImages[$name] -eq $Images[$name]) 'runtime must restore the previous tested image digests'
    }
}

function Invoke-TestCleanup {
    [CmdletBinding()]
    param([string[]]$LiteralPath, [switch]$Force, [switch]$Recurse)
    foreach ($path in $LiteralPath) {
        if ($path.EndsWith('.tmp', [StringComparison]::Ordinal)) {
            $global:edugraphTestCleanupAttempts.Add($path)
            $metadata = $path -like '*.json.*.tmp'
            if (($metadata -and $global:edugraphTestLockMetadataCleanup) -or
                (-not $metadata -and $global:edugraphTestLockOverrideCleanup)) {
                if ($metadata) { $global:edugraphTestLockMetadataCleanup = $false }
                else { $global:edugraphTestLockOverrideCleanup = $false }
                $global:edugraphTestCleanupBlocked.Add($path)
                throw [IO.IOException]::new('Simulated locked temporary file')
            }
        }
    }
    Microsoft.PowerShell.Management\Remove-Item @PSBoundParameters
}

function Invoke-DeploymentLockProbe([string]$ProbeEnv, [string]$Marker, [string]$ProbeRelease = '') {
    $manifests = New-TestCIManifests ('sha-' + ('a' * 40)) ('sha-' + ('b' * 40))
    $job = Start-Job -ScriptBlock {
        param($DeployScript, $EnvFile, $MarkerPath, $ReleasePath, $BackendManifest, $FrontendManifest)
        $ErrorActionPreference = 'Stop'
        function docker {
            Set-Content -LiteralPath $MarkerPath -Value 'Docker called'
            $global:LASTEXITCODE = 0
        }
        function Invoke-TestDockerProcess {
            param($FilePath, $Arguments, $TimeoutSeconds)
            $output = @(& docker @Arguments) -join [Environment]::NewLine
            return @{ ExitCode = $global:LASTEXITCODE; Output = $output; Error = '' }
        }
        Set-Alias -Name Invoke-DeploymentProcess -Value Invoke-TestDockerProcess
        $parameters = @{
            EnvFile = $EnvFile; BackendTag = 'sha-' + ('a' * 40)
            FrontendTag = 'sha-' + ('b' * 40); ValidateOnly = $true
            BackendManifest = $BackendManifest; FrontendManifest = $FrontendManifest
        }
        if ($ReleasePath) { $parameters.ReleaseFile = $ReleasePath }
        try {
            & $DeployScript @parameters
            'Probe completed'
        } catch { $_.Exception.Message }
    } -ArgumentList $deployScript, $ProbeEnv, $Marker, $ProbeRelease, $manifests.backend, $manifests.frontend
    try {
        $completed = Wait-Job -Job $job -Timeout 30
        Assert-True ($null -ne $completed) 'deployment lock probe must complete promptly'
        return Receive-Job -Job $job -ErrorAction Stop
    } finally {
        Stop-Job -Job $job
        Remove-Job -Job $job
    }
}

try {
    # Intercept HTTP only in this test scope; never call the real origin.
    Set-Alias -Name Invoke-DeploymentWebRequest -Value Invoke-TestWebRequest -Scope Script
    Set-Alias -Name Start-Sleep -Value Invoke-TestRetryDelay -Scope Script
    Set-Alias -Name Invoke-DeploymentProcess -Value Invoke-TestDockerProcess -Scope Script
    Set-Alias -Name Get-DeploymentElapsedSeconds -Value Invoke-TestDeploymentElapsedSeconds -Scope Script
    $first = 'sha-' + ('a' * 40)
    $second = 'sha-' + ('b' * 40)
    $third = 'sha-' + ('c' * 40)
    Invoke-TestDeployment -EnvFile $testEnv -BackendTag $first -FrontendTag $first -ValidateOnly
    Assert-True ($global:edugraphTestCalls.Count -eq 1) 'ValidateOnly must not pull or deploy'

    foreach ($mode in @('running', 'stopped', 'query-failed')) {
        $global:edugraphTestRunningImages = if ($mode -eq 'running') { @{ frontend = 'existing-live-image' } } else { @{} }
        $global:edugraphTestStoppedProjectContainer = $mode -eq 'stopped'
        $global:edugraphTestFailProjectQuery = $mode -eq 'query-failed'
        $callCount = $global:edugraphTestCalls.Count
        $missingStateFailed = $false
        try { Invoke-TestDeployment -EnvFile $testEnv -BackendTag $first -FrontendTag $first } catch {
            Assert-True ($_.Exception.Message -like 'Existing edugraph containers have no current release record*' -or
                $_.Exception.Message -eq 'Cannot determine whether the edugraph project is empty.') 'missing metadata must explain why deployment cannot proceed'
            $missingStateFailed = $true
        }
        Assert-True $missingStateFailed 'an existing or uninspectable project must not be treated as empty'
        $unsafeCalls = @($global:edugraphTestCalls | Select-Object -Skip $callCount | Where-Object { $_.arguments -contains 'up' -or $_.arguments -contains 'pull' -or $_.arguments -contains 'stop' })
        Assert-True ($unsafeCalls.Count -eq 0) 'missing metadata protection must run before runtime changes or pulls'
        if ($mode -eq 'running') { Assert-True ($global:edugraphTestRunningImages.frontend -eq 'existing-live-image') 'missing metadata must preserve the live containers' }
    }
    $global:edugraphTestStoppedProjectContainer = $false
    $global:edugraphTestFailProjectQuery = $false
    $global:edugraphTestRunningImages = @{}
    $invalidStatePath = Join-Path $testRoot 'current-release.json'
    foreach ($invalidState in @(' ', 'null', '{}', 'invalid-json')) {
        Set-Content -LiteralPath $invalidStatePath -Value $invalidState
        $invalidStateFailed = $false
        try { Invoke-TestDeployment -EnvFile $testEnv -BackendTag $first -FrontendTag $first } catch {
            Assert-True ($_.Exception.Message -like 'Cannot use current release record*') 'invalid metadata must fail explicitly'
            $invalidStateFailed = $true
        }
        Assert-True $invalidStateFailed 'an invalid record must not select first-deploy recovery'
    }
    Remove-Item -LiteralPath $invalidStatePath

    $env:BACKEND_TAG = 'original-value'
    Invoke-TestDeployment -EnvFile $testEnv -BackendTag $first -FrontendTag $second -Gpu -Tunnel
    $statePath = Join-Path $testRoot 'current-release.json'
    $state = Get-Content $statePath -Raw | ConvertFrom-Json
    Assert-True ($state.backendTag -eq $first -and $state.frontendTag -eq $second) 'successful release must be persisted'
    Assert-True ($state.gpu -and $state.tunnel) 'deployment modes must be persisted'
    Assert-True (@($state.images.PSObject.Properties).Count -eq 5) 'every active service must persist its digest'
    foreach ($image in $state.images.PSObject.Properties.Value) {
        Assert-True ($image -match '@sha256:[a-f0-9]{64}$') 'release images must use immutable digests'
    }
    $originalImages = $state.images | ConvertTo-Json -Compress
    Assert-True ($env:BACKEND_TAG -eq 'original-value') 'caller environment must be restored'
    $up = @($global:edugraphTestCalls | Where-Object { $_.arguments -contains 'up' })[-1]
    Assert-True (($up.arguments -join ' ') -match 'compose.gpu.yml') 'GPU override must be used'

    $scenarioStart = $global:edugraphTestCalls.Count
    $global:edugraphTestFailNextUp = $true
    $global:edugraphTestCheckLockAtOrigin = $true
    $failed = $false
    # A rerun can move the old tags; rollback must still use the original digests.
    $global:edugraphTestTagGeneration = 'rerun'
    try { Invoke-TestDeployment -EnvFile $testEnv -BackendTag $third -FrontendTag $third } catch {
        Assert-True ($_.Exception.Message -eq 'Docker Compose failed: up') 'rollback must preserve the original deployment error'
        $failed = $true
    }
    Assert-True $failed 'a failed release must fail the job even after successful rollback'
    Assert-True ($global:edugraphTestLockCheckCount -eq 2) 'both successful deploy and automatic rollback must retain the project lock'
    $scenarioUps = @($global:edugraphTestCalls | Select-Object -Skip $scenarioStart | Where-Object { $_.arguments -contains 'up' })
    Assert-True ($scenarioUps.Count -eq 2) 'this failure scenario must attempt the new release and then invoke rollback'
    $attempt = $scenarioUps[0]
    Assert-True ($attempt.backend -eq $third -and $attempt.frontend -eq $third) 'the first up must attempt the third release'
    Assert-True (($attempt.arguments -join ' ') -notmatch 'compose.gpu.yml|--profile tunnel') 'the first up must use the requested CPU/tunnel-off modes'
    $up = $scenarioUps[1]
    Assert-True ($up.backend -eq $first -and $up.frontend -eq $second) 'rollback must use the last successful tags'
    Assert-True (($up.arguments -join ' ') -match 'compose.gpu.yml.*--profile tunnel') 'rollback must restore GPU/tunnel modes'
    foreach ($image in $state.images.PSObject.Properties) {
        Assert-True ($up.images[$image.Name] -eq $image.Value) 'rollback must restore the tested digest even when its tag has moved'
    }
    $state = Get-Content $statePath -Raw | ConvertFrom-Json
    Assert-True ($state.backendTag -eq $first) 'failed release must not overwrite state'
    Assert-True (($state.images | ConvertTo-Json -Compress) -eq $originalImages) 'failed release must not overwrite saved digests'

    $upCount = @($global:edugraphTestCalls | Where-Object { $_.arguments -contains 'up' }).Count
    $global:edugraphTestFailPull = $true
    $pullFailed = $false
    try { Invoke-TestDeployment -EnvFile $testEnv -BackendTag $third -FrontendTag $third } catch {
        Assert-True ($_.Exception.Message -eq 'Docker Compose failed: pull') 'pull must report its original error'
        $pullFailed = $true
    }
    Assert-True $pullFailed 'a failed pull must fail the job'
    Assert-True (@($global:edugraphTestCalls | Where-Object { $_.arguments -contains 'up' }).Count -eq $upCount) 'failed pulls must not replace containers'
    $global:edugraphTestFailPull = $false

    $global:edugraphTestNoDigest = $true
    $digestFailed = $false
    try { Invoke-TestDeployment -EnvFile $testEnv -BackendTag $third -FrontendTag $third -Tunnel } catch {
        Assert-True ($_.Exception.Message -like 'No repository digest found*') 'missing digest must report its cause'
        $digestFailed = $true
    }
    $global:edugraphTestNoDigest = $false
    Assert-True $digestFailed 'images without digests must not deploy'
    Assert-True (@($global:edugraphTestCalls | Where-Object { $_.arguments -contains 'up' }).Count -eq $upCount) 'digest resolution must finish before replacing containers'

    $global:edugraphTestWrongImage = $true
    $imageFailed = $false
    try { Invoke-TestDeployment -EnvFile $testEnv -BackendTag $third -FrontendTag $third } catch {
        Assert-True ($_.Exception.Message -like 'Running image differs*') 'unexpected running image must report its cause'
        $imageFailed = $true
    }
    Assert-True $imageFailed 'a healthy container with the wrong image must not become a successful release'

    Invoke-TestDeployment -EnvFile $testEnv -BackendTag $third -FrontendTag $third
    $previous = Get-Content (Join-Path $testRoot 'previous-release.json') -Raw | ConvertFrom-Json
    Assert-True ($previous.backendTag -eq $first) 'successful update must retain rollback state'
    Assert-True (($previous.images | ConvertTo-Json -Compress) -eq $originalImages) 'previous release must retain exact tested digests'
    $offState = Get-Content $statePath -Raw | ConvertFrom-Json
    Assert-True (-not $offState.tunnel) 'tunnel-off release must record its target mode'
    Assert-True (-not $global:edugraphTestRunningImages.ContainsKey('cloudflared')) 'on-to-off deployment must explicitly stop the existing connector'
    $offReleasePath = Join-Path $testRoot 'saved-tunnel-off-release.json'
    Copy-Item -LiteralPath $statePath -Destination $offReleasePath

    $callCount = $global:edugraphTestCalls.Count
    $global:edugraphTestFailPull = $true
    Invoke-TestDeployment -EnvFile $testEnv -ReleaseFile (Join-Path $testRoot 'previous-release.json')
    $global:edugraphTestFailPull = $false
    $manualCalls = @($global:edugraphTestCalls | Select-Object -Skip $callCount)
    Assert-True (@($manualCalls | Where-Object { $_.arguments -contains 'pull' }).Count -eq 0) 'manual rollback must use cached digests without registry access'
    Assert-True (@($manualCalls | Where-Object { $_.arguments -contains '{{json .RepoDigests}}' }).Count -eq 0) 'manual rollback must not resolve mutable tags'
    $state = Get-Content $statePath -Raw | ConvertFrom-Json
    Assert-True (($state.images | ConvertTo-Json -Compress) -eq $originalImages) 'manual rollback must restore exact saved digests'
    Assert-True ($state.gpu -and $state.tunnel) 'manual rollback must restore saved modes'
    Assert-True ($global:edugraphTestRunningImages.ContainsKey('cloudflared')) 'off-to-on rollback must restart the connector through up'

    Invoke-TestDeployment -EnvFile $testEnv -ReleaseFile $offReleasePath
    Assert-True (-not $global:edugraphTestRunningImages.ContainsKey('cloudflared')) 'manual rollback to tunnel-off must stop the connector'
    $offStateJson = Get-Content $statePath -Raw
    $global:edugraphTestFailNextUp = $true
    $tunnelFailed = $false
    try { Invoke-TestDeployment -EnvFile $testEnv -BackendTag $first -FrontendTag $second -Tunnel } catch {
        Assert-True ($_.Exception.Message -eq 'Docker Compose failed: up') 'failed tunnel-on release must preserve its original error'
        $tunnelFailed = $true
    }
    Assert-True $tunnelFailed 'partially started tunnel-on release must fail'
    Assert-True (-not $global:edugraphTestRunningImages.ContainsKey('cloudflared')) 'automatic rollback to tunnel-off must stop a partially started connector'
    Assert-True ((Get-Content $statePath -Raw) -eq $offStateJson) 'failed tunnel-on release must preserve the tunnel-off state'

    $global:edugraphTestFailNextStop = $true
    $stopFailed = $false
    try { Invoke-TestDeployment -EnvFile $testEnv -BackendTag $first -FrontendTag $second } catch {
        Assert-True ($_.Exception.Message -eq 'Cannot stop project Cloudflare connector.') 'connector stop failure must remain visible'
        $stopFailed = $true
    }
    Assert-True $stopFailed 'failed connector stop must fail deployment'
    Assert-True ((Get-Content $statePath -Raw) -eq $offStateJson) 'failed connector stop must not persist a new release'

    $global:edugraphTestRunningImages['cloudflared'] = 'stale-connector'
    $global:edugraphTestIgnoreNextStop = $true
    $stillRunningFailed = $false
    try { Invoke-TestDeployment -EnvFile $testEnv -BackendTag $first -FrontendTag $second } catch {
        Assert-True ($_.Exception.Message -eq 'Cloudflare connector is still running in tunnel-off mode.') 'still-running connector must be detected'
        $stillRunningFailed = $true
    }
    Assert-True $stillRunningFailed 'stop exit code alone must not prove the connector is stopped'
    Assert-True (-not $global:edugraphTestRunningImages.ContainsKey('cloudflared')) 'rollback must reconcile a connector left running after stop'
    Assert-True ((Get-Content $statePath -Raw) -eq $offStateJson) 'still-running connector must not persist a new release'
    foreach ($call in @($global:edugraphTestCalls | Where-Object { $_.arguments -contains 'stop' })) {
        Assert-True (($call.arguments -join ' ') -match '--project-name edugraph') 'connector stop must remain scoped to this Compose project'
        Assert-True (($call.arguments -join ' ') -match '--profile tunnel stop cloudflared') 'connector stop must explicitly target its disabled profile service'
    }
    foreach ($call in @($global:edugraphTestCalls | Where-Object { $_.arguments -contains 'up' })) {
        Assert-True (($call.arguments -join ' ') -match '--pull never') 'up must never re-resolve tags'
        Assert-True ($call.images.Count -ge 4) 'every deployment and rollback must use a digest override'
    }

    $publicCount = @($global:edugraphTestHttpCalls | Where-Object public).Count
    $global:edugraphTestPublicFailures = 4
    Invoke-TestDeployment -EnvFile $testEnv -BackendTag $third -FrontendTag $third
    Assert-True (@($global:edugraphTestHttpCalls | Where-Object public).Count -eq $publicCount) 'tunnel-off mode must use loopback checks only'
    Assert-True ($global:edugraphTestPublicFailures -eq 4) 'tunnel-off mode must not depend on public connectivity'

    $global:edugraphTestPublicFailures = 2
    $delayCount = $global:edugraphTestRetryDelays
    Invoke-TestDeployment -EnvFile $testEnv -BackendTag $first -FrontendTag $second -Tunnel
    Assert-True ($global:edugraphTestRetryDelays -eq $delayCount + 2) 'transient public failures must retry before accepting the release'
    $publicState = Get-Content $statePath -Raw
    $httpCount = $global:edugraphTestHttpCalls.Count
    $global:edugraphTestPublicFailures = 4
    $publicFailed = $false
    try { Invoke-TestDeployment -EnvFile $testEnv -BackendTag $third -FrontendTag $third -Tunnel } catch {
        Assert-True ($_.Exception.Message -like 'Public route check failed*after 4 attempts*') 'unavailable public route must report bounded failure'
        $publicFailed = $true
    }
    Assert-True $publicFailed 'loopback success must not accept an unavailable public route'
    Assert-True ((Get-Content $statePath -Raw) -eq $publicState) 'public failure must not overwrite the last successful release'
    $publicCalls = @($global:edugraphTestHttpCalls | Select-Object -Skip $httpCount | Where-Object public)
    Assert-True ($publicCalls.Count -eq 7) 'four failed attempts must be followed by all three public rollback probes'
    Assert-True ($publicCalls[-1].path -eq '/api/users/me') 'tunnel-enabled rollback must verify its protected public API'

    $global:edugraphTestPublicBodyTimeouts = 4
    $callCount = $global:edugraphTestCalls.Count
    $delayCount = $global:edugraphTestRetryDelays
    $bodyTimeoutFailed = $false
    try { Invoke-TestDeployment -EnvFile $testEnv -BackendTag $third -FrontendTag $third -Tunnel } catch {
        Assert-True ($_.Exception.Message -like 'Public route check failed*full-response deadline*') 'body timeout must report the failed smoke check'
        $bodyTimeoutFailed = $true
    }
    Assert-True $bodyTimeoutFailed 'stalled public bodies must not be accepted'
    Assert-True ($global:edugraphTestRetryDelays -eq $delayCount + 3) 'body timeouts must advance through bounded retries'
    $bodyUps = @($global:edugraphTestCalls | Select-Object -Skip $callCount | Where-Object { $_.arguments -contains 'up' })
    Assert-True ($bodyUps.Count -eq 2 -and $bodyUps[-1].backend -eq $first) 'exhausted body retries must restore the prior runtime'
    Assert-True ((Get-Content $statePath -Raw) -eq $publicState) 'body timeout must preserve release records'

    $global:edugraphTestPublicWrongAssets = 4
    $wrongUpstreamFailed = $false
    try { Invoke-TestDeployment -EnvFile $testEnv -BackendTag $third -FrontendTag $third -Tunnel } catch {
        Assert-True ($_.Exception.Message -like '*deployed frontend assets*') 'wrong frontend upstream must explain the asset mismatch'
        $wrongUpstreamFailed = $true
    }
    Assert-True $wrongUpstreamFailed 'public HTTP 200 alone must not prove the correct frontend is served'
    Assert-True ((Get-Content $statePath -Raw) -eq $publicState) 'wrong public upstream must not become the current release'

    $global:edugraphTestPublicStatus = 302
    $global:edugraphTestPublicFailures = 4
    $redirectFailed = $false
    try { Invoke-TestDeployment -EnvFile $testEnv -BackendTag $third -FrontendTag $third -Tunnel } catch {
        Assert-True ($_.Exception.Message -like 'Public route check failed*') 'public redirect must fail the route check'
        $redirectFailed = $true
    }
    Assert-True $redirectFailed 'redirects to login or another site must not pass the public probe'
    $global:edugraphTestPublicStatus = 503

    $global:edugraphTestWrongImage = $true
    $global:edugraphTestPublicFailures = 4
    $rollbackPublicFailed = $false
    $previousState = Get-Content (Join-Path $testRoot 'previous-release.json') -Raw
    $callCount = $global:edugraphTestCalls.Count
    $rollbackWarnings = @()
    try { Invoke-TestDeployment -EnvFile $testEnv -BackendTag $third -FrontendTag $third -WarningVariable rollbackWarnings } catch {
        Assert-True ($_.Exception.Message -like 'Running image differs*') 'rollback failure must not replace the original deployment error'
        Assert-True ($_.Exception.Data['RollbackFailure'] -like 'Public route check failed*') 'secondary rollback error must remain available separately'
        $rollbackPublicFailed = $true
    }
    Assert-True $rollbackPublicFailed 'tunnel-enabled rollback cannot succeed with only loopback checks'
    Assert-True (($rollbackWarnings -join ' ') -like '*restoration could not be confirmed*') 'failed rollback must report uncertain restoration'
    Assert-True ((Get-Content $statePath -Raw) -eq $publicState) 'failed public rollback must not publish new state'
    Assert-True ((Get-Content (Join-Path $testRoot 'previous-release.json') -Raw) -eq $previousState) 'failed rollback must retain previous-release.json for manual recovery'
    Assert-RecoveryOverrides @($global:edugraphTestCalls | Select-Object -Skip $callCount)

    $global:edugraphTestRemainingUpFailures = 2
    $callCount = $global:edugraphTestCalls.Count
    $doubleUpFailed = $false
    try { Invoke-TestDeployment -EnvFile $testEnv -BackendTag $third -FrontendTag $third } catch {
        Assert-True ($_.Exception.Message -eq 'Docker Compose failed: up') 'rollback startup failure must preserve the original up error'
        Assert-True ($_.Exception.Data['RollbackFailure'] -eq 'Docker Compose failed: up') 'rollback startup error must remain available separately'
        $doubleUpFailed = $true
    }
    Assert-True $doubleUpFailed 'both startup failures must leave the deployment failed'
    Assert-True ($global:edugraphTestRemainingUpFailures -eq 0) 'test must exercise both deployment and rollback startup failure'
    Assert-True ((Get-Content $statePath -Raw) -eq $publicState) 'double startup failure must retain current-release.json'
    Assert-True ((Get-Content (Join-Path $testRoot 'previous-release.json') -Raw) -eq $previousState) 'double startup failure must retain previous-release.json'
    Assert-RecoveryOverrides @($global:edugraphTestCalls | Select-Object -Skip $callCount)
    foreach ($call in @($global:edugraphTestHttpCalls | Where-Object public)) {
        Assert-True ($call.uri.StartsWith('https://edugraph-rag.io.vn/')) 'public checks must use the configured HTTPS hostname'
        Assert-True ($call.timeout -eq 10) 'each public request must have a finite timeout'
        Assert-True ($call.redirectsSpecified -and $call.redirects -eq 0) 'public checks must explicitly disable redirects'
        Assert-True ($call.headers['Cache-Control'] -eq 'no-cache') 'public checks must bypass cached responses'
    }

    # Publication faults happen after healthy activation, and must reconcile both
    # runtime and release records. Use real file renames except at injected faults.
    Invoke-TestDeployment -EnvFile $testEnv -BackendTag $first -FrontendTag $second -Tunnel
    $baselineCurrent = Get-Content $statePath -Raw
    $baselinePrevious = Get-Content (Join-Path $testRoot 'previous-release.json') -Raw
    $baselineImages = ($baselineCurrent | ConvertFrom-Json -AsHashtable).images
    Set-Alias -Name New-MetadataStage -Value Invoke-TestMetadataStage -Scope Script
    Set-Alias -Name Publish-ReleaseMetadata -Value Invoke-TestMetadataPublish -Scope Script

    $global:edugraphTestFailMetadataStage = $true
    $upCount = @($global:edugraphTestCalls | Where-Object { $_.arguments -contains 'up' }).Count
    $stageFailed = $false
    try { Invoke-TestDeployment -EnvFile $testEnv -BackendTag $third -FrontendTag $third } catch {
        Assert-True ($_.Exception.Message -eq 'Simulated metadata staging failure') 'staging failure must report its cause'
        $stageFailed = $true
    }
    Assert-True $stageFailed 'failed metadata staging must fail deployment'
    Assert-True (@($global:edugraphTestCalls | Where-Object { $_.arguments -contains 'up' }).Count -eq $upCount) 'staging failure must happen before changing containers'
    Assert-TestRuntime $baselineImages

    foreach ($destination in @('previous-release.json', 'current-release.json')) {
        $global:edugraphTestPublicationFailures.Enqueue($destination)
        $publicationFailed = $false
        try { Invoke-TestDeployment -EnvFile $testEnv -BackendTag $third -FrontendTag $third } catch {
            Assert-True ($_.Exception.Message -eq "Simulated publication failure: $destination") 'publication failure must preserve its original error'
            $publicationFailed = $true
        }
        Assert-True $publicationFailed 'either publication failure must fail deployment'
        Assert-True ((Get-Content $statePath -Raw) -eq $baselineCurrent) 'publication failure must preserve the prior current record'
        Assert-True ((Get-Content (Join-Path $testRoot 'previous-release.json') -Raw) -eq $baselinePrevious) 'publication failure must restore the prior rollback record'
        Assert-TestRuntime $baselineImages
    }

    $global:edugraphTestPublicationFailures.Enqueue('current-release.json')
    $global:edugraphTestFailRollbackOnPublish = $true
    $callCount = $global:edugraphTestCalls.Count
    $publicationRollbackFailed = $false
    $publicationWarnings = @()
    try { Invoke-TestDeployment -EnvFile $testEnv -BackendTag $third -FrontendTag $third -WarningVariable publicationWarnings } catch {
        Assert-True ($_.Exception.Message -eq 'Simulated publication failure: current-release.json') 'failed rollback must not mask the state-publication error'
        Assert-True ($_.Exception.Data['RollbackFailure'] -eq 'Docker Compose failed: up') 'failed runtime reconciliation must remain available separately'
        $publicationRollbackFailed = $true
    }
    Assert-True $publicationRollbackFailed 'publication plus runtime recovery failure must fail deployment'
    Assert-True (($publicationWarnings -join ' ') -like '*Runtime/state reconciliation failed*') 'failed reconciliation must be explicit'
    Assert-True ((Get-Content $statePath -Raw) -eq $baselineCurrent) 'failed reconciliation must retain the prior current record'
    Assert-True ((Get-Content (Join-Path $testRoot 'previous-release.json') -Raw) -eq $baselinePrevious) 'metadata restoration must run even when runtime recovery fails'
    Assert-RecoveryOverrides @($global:edugraphTestCalls | Select-Object -Skip $callCount)

    $global:edugraphTestPublicationFailures.Enqueue('current-release.json')
    $global:edugraphTestPublicationFailures.Enqueue('previous-release.json')
    $callCount = $global:edugraphTestCalls.Count
    $metadataRecoveryFailed = $false
    $metadataWarnings = @()
    try { Invoke-TestDeployment -EnvFile $testEnv -BackendTag $third -FrontendTag $third -WarningVariable metadataWarnings } catch {
        Assert-True ($_.Exception.Message -eq 'Simulated publication failure: current-release.json') 'metadata recovery failure must preserve the original publication error'
        Assert-True ($_.Exception.Data['StateRecoveryFailure'] -eq 'Simulated publication failure: previous-release.json') 'metadata recovery error must remain available separately'
        $metadataRecoveryFailed = $true
    }
    Assert-True $metadataRecoveryFailed 'failed restoration of the rollback record must fail deployment'
    Assert-True (($metadataWarnings -join ' ') -like '*Release-state reconciliation failed*') 'failed metadata reconciliation must be explicit'
    Assert-True ((Get-Content $statePath -Raw) -eq $baselineCurrent) 'failed metadata recovery must preserve current-release.json'
    Assert-TestRuntime $baselineImages
    Assert-RecoveryOverrides @($global:edugraphTestCalls | Select-Object -Skip $callCount)
    $backups = @(Get-ChildItem -LiteralPath $testRoot -Filter 'previous-release.json.*.tmp' | Where-Object { (Get-Content -LiteralPath $_.FullName -Raw) -eq $baselinePrevious })
    Assert-True ($backups.Count -gt 0) 'manual recovery must retain a staged copy of the old rollback record'
    # Restore this isolated test fixture after intentionally failing its recovery.
    [IO.File]::WriteAllText((Join-Path $testRoot 'previous-release.json'), $baselinePrevious)

    $firstRoot = Join-Path $testRoot 'first-deploy'
    New-Item -ItemType Directory -Path $firstRoot | Out-Null
    $firstEnv = Join-Path $firstRoot 'production.env'
    Set-Content -LiteralPath $firstEnv -Value '# isolated first deployment'
    $global:edugraphTestRunningImages = @{}
    $global:edugraphTestPublicationFailures.Enqueue('current-release.json')
    $firstPublicationFailed = $false
    try { Invoke-TestDeployment -EnvFile $firstEnv -BackendTag $third -FrontendTag $third } catch {
        Assert-True ($_.Exception.Message -eq 'Simulated publication failure: current-release.json') 'first-release publication failure must preserve its cause'
        $firstPublicationFailed = $true
    }
    Assert-True $firstPublicationFailed 'first deployment must fail when state cannot be published'
    Assert-True ($global:edugraphTestRunningImages.Count -eq 0) 'first-release publication failure must stop the newly activated services'
    Assert-True (-not (Test-Path (Join-Path $firstRoot 'current-release.json'))) 'first-release failure must not create a successful record'
    Remove-Item -LiteralPath Alias:New-MetadataStage, Alias:Publish-ReleaseMetadata

    $legacyPath = Join-Path $testRoot 'legacy-release.json'
    @{ backendTag = $first; frontendTag = $second; gpu = $true; tunnel = $true } | ConvertTo-Json | Set-Content $legacyPath
    $legacyFailed = $false
    try { Invoke-TestDeployment -EnvFile $testEnv -ReleaseFile $legacyPath } catch {
        Assert-True ($_.Exception.Message -like 'Release must contain immutable*') 'tag-only state must explain why rollback is unsafe'
        $legacyFailed = $true
    }
    Assert-True $legacyFailed 'legacy tag-only releases must not silently fall back to mutable tags'

    $callCount = $global:edugraphTestCalls.Count
    $missingManifestFailed = $false
    try { & $deployScript -EnvFile $testEnv -BackendTag $first -FrontendTag $second } catch {
        Assert-True ($_.Exception.Message -like 'Fresh deployments require both*') 'tag-only deployments must explain that complete manifests are required'
        $missingManifestFailed = $true
    }
    Assert-True $missingManifestFailed 'fresh deployment must not resolve a release from mutable tags'
    Assert-True ($global:edugraphTestCalls.Count -eq $callCount) 'missing build selection must fail before Docker'

    $chosen = New-TestCIManifests $first $second
    $backendManifest = Get-Content $chosen.backend -Raw | ConvertFrom-Json -AsHashtable
    $gpuReference = $backendManifest.images['edugraph-ai-core-gpu']
    $backendManifest.images.Remove('edugraph-ai-core-gpu')
    $backendManifest | ConvertTo-Json -Depth 4 | Set-Content $chosen.backend
    $incompleteFailed = $false
    try { & $deployScript -EnvFile $testEnv -BackendManifest $chosen.backend -FrontendManifest $chosen.frontend } catch {
        Assert-True ($_.Exception.Message -like 'Invalid or incomplete backend*') 'incomplete manifests must be rejected'
        $incompleteFailed = $true
    }
    Assert-True $incompleteFailed 'CPU deployments must also require the complete backend build set'
    Assert-True ($global:edugraphTestCalls.Count -eq $callCount) 'incomplete manifests must fail before Docker'
    $backendManifest.images['edugraph-ai-core-gpu'] = $gpuReference
    $backendManifest | ConvertTo-Json -Depth 4 | Set-Content $chosen.backend
    $tagMismatchFailed = $false
    try { & $deployScript -EnvFile $testEnv -BackendTag $third -BackendManifest $chosen.backend -FrontendManifest $chosen.frontend } catch {
        Assert-True ($_.Exception.Message -like 'Commit tags do not match*') 'lookup tags must match the selected build'
        $tagMismatchFailed = $true
    }
    Assert-True $tagMismatchFailed 'a different tag must not override the selected build manifest'

    # Simulate tag replacement after the operator downloaded the chosen manifests.
    $global:edugraphTestTagGeneration = 'partially-published-rerun'
    $callCount = $global:edugraphTestCalls.Count
    & $deployScript -EnvFile $testEnv -BackendManifest $chosen.backend -FrontendManifest $chosen.frontend -Gpu
    $chosenCalls = @($global:edugraphTestCalls | Select-Object -Skip $callCount)
    Assert-True (@($chosenCalls | Where-Object { $_.arguments -contains '{{json .RepoDigests}}' }).Count -eq 0) 'application digests must never be resolved again from commit tags'
    $chosenUp = @($chosenCalls | Where-Object { $_.arguments -contains 'up' })[0]
    Assert-True ($chosenUp.images['ai-core'] -eq $gpuReference) 'GPU deployment must use the selected manifest variant'
    foreach ($name in @('core-service', 'gateway-service')) {
        Assert-True ($chosenUp.images[$name] -eq $backendManifest.images[$name]) 'tag replacement must not mix images from another build'
    }
    $frontendManifest = Get-Content $chosen.frontend -Raw | ConvertFrom-Json -AsHashtable
    Assert-True ($chosenUp.images.frontend -eq $frontendManifest.images.frontend) 'frontend must also use the exact selected build digest'
    $chosenState = Get-Content $statePath -Raw | ConvertFrom-Json
    Assert-True ($chosenState.builds.backend.runId -eq '123' -and $chosenState.builds.backend.attempt -eq '1') 'release state must preserve selected CI build identity'

    $deadlineBaseline = Get-Content $statePath -Raw
    $deadlineImages = ($deadlineBaseline | ConvertFrom-Json -AsHashtable).images
    $processStart = $global:edugraphTestProcessCalls.Count
    $callCount = $global:edugraphTestCalls.Count
    $expiredFailed = $false
    try { Invoke-TestDeployment -EnvFile $testEnv -BackendTag $third -FrontendTag $third -DeadlineUtc ([DateTimeOffset]::UtcNow.AddMinutes(29)) } catch {
        Assert-True ($_.Exception.Message -eq 'Deployment forward deadline exceeded.') 'elapsed checkout time must not consume the recovery reserve'
        $expiredFailed = $true
    }
    Assert-True $expiredFailed 'an invocation with only recovery time remaining must reject forward work'
    Assert-True ($global:edugraphTestCalls.Count -eq $callCount) 'an expired forward budget must fail before Docker'

    $global:edugraphTestTimeoutForwardPull = $true
    $slowPullFailed = $false
    try { Invoke-TestDeployment -EnvFile $testEnv -BackendTag $third -FrontendTag $third } catch {
        Assert-True ($_.Exception.Message -eq 'Simulated Docker command budget exceeded') 'pull timeout must report its cause'
        $slowPullFailed = $true
    }
    Assert-True $slowPullFailed 'a hung pull must not wait for the workflow deadline'
    $pullCalls = @($global:edugraphTestCalls | Select-Object -Skip $callCount)
    Assert-True (@($pullCalls | Where-Object { $_.arguments -contains 'up' }).Count -eq 0) 'pull timeout must not activate the new release'
    Assert-TestRuntime $deadlineImages
    $global:edugraphTestElapsedSeconds = 0

    $global:edugraphTestAdvanceAfterPull = 1450
    $global:edugraphTestTimeoutActivation = $true
    $processStart = $global:edugraphTestProcessCalls.Count
    $lateActivationFailed = $false
    try { Invoke-TestDeployment -EnvFile $testEnv -BackendTag $third -FrontendTag $third } catch {
        Assert-True ($_.Exception.Message -eq 'Simulated Docker command budget exceeded') 'rollback must preserve the activation timeout'
        Assert-True (-not $_.Exception.Data.Contains('RollbackFailure')) 'reserved recovery time must allow the previous release to be restored'
        $lateActivationFailed = $true
    }
    Assert-True $lateActivationFailed 'late activation must stop before exhausting recovery time'
    $deadlineCalls = @($global:edugraphTestProcessCalls | Select-Object -Skip $processStart)
    $forwardUp = @($deadlineCalls | Where-Object { -not $_.recovering -and $_.arguments -contains 'up' })[0]
    $recoveryUp = @($deadlineCalls | Where-Object { $_.recovering -and $_.arguments -contains 'up' })[0]
    Assert-True ($forwardUp.timeout -lt 60) 'activation must use only the short forward budget remaining after a slow pull'
    $waitIndex = [Array]::IndexOf($forwardUp.arguments, '--wait-timeout')
    Assert-True ([int]$forwardUp.arguments[$waitIndex + 1] -le $forwardUp.timeout - 5) 'Compose health wait must fit within the process timeout'
    Assert-True ($recoveryUp.timeout -gt 1200) 'rollback must retain its separate full activation budget'
    foreach ($call in @($deadlineCalls | Where-Object { $_.recovering -and $_.arguments -contains 'pull' })) {
        Assert-True ($call.timeout -le 120) 'rollback registry pulls must have their own finite cap'
    }
    Assert-TestRuntime $deadlineImages
    Assert-True ((Get-Content $statePath -Raw) -eq $deadlineBaseline) 'activation timeout must not publish new state'
    $global:edugraphTestElapsedSeconds = 0

    $global:edugraphTestAdvanceAfterPull = 1450
    $global:edugraphTestTimeoutActivation = $true
    $global:edugraphTestTimeoutRecoveryPull = $true
    $missingRecoveryImage = @($deadlineImages.Values)[0]
    $null = $global:edugraphTestMissingImages.Add($missingRecoveryImage)
    $callCount = $global:edugraphTestCalls.Count
    $recoveryTimeoutFailed = $false
    try { Invoke-TestDeployment -EnvFile $testEnv -BackendTag $third -FrontendTag $third } catch {
        Assert-True ($_.Exception.Message -eq 'Simulated Docker command budget exceeded') 'recovery timeout must preserve the original activation error'
        Assert-True ($_.Exception.Data['RollbackFailure'] -eq 'Simulated Docker command budget exceeded') 'recovery timeout must be reported separately'
        $recoveryTimeoutFailed = $true
    }
    Assert-True $recoveryTimeoutFailed 'a hung recovery pull must fail explicitly before workflow cancellation'
    # A missing-image pull now fails before Compose can use the rollback override.
    Assert-RecoveryOverrides @($global:edugraphTestCalls | Select-Object -Skip $callCount) 1
    Assert-True ((Get-Content $statePath -Raw) -eq $deadlineBaseline) 'recovery timeout must retain recovery records'
    $global:edugraphTestMissingImages.Clear()
    $global:edugraphTestElapsedSeconds = 0
    foreach ($call in $global:edugraphTestProcessCalls) {
        Assert-True ($call.timeout -gt 0 -and $call.timeout -le 1205) 'every Docker invocation must have a finite operation budget'
    }
    $httpStart = $global:edugraphTestHttpCalls.Count
    $global:edugraphTestAdvanceAtOrigin = 1493
    Invoke-TestDeployment -EnvFile $testEnv -BackendTag $third -FrontendTag $third -Tunnel
    $latePublicCalls = @($global:edugraphTestHttpCalls | Select-Object -Skip $httpStart | Where-Object public)
    Assert-True ($latePublicCalls.Count -eq 3) 'late public smoke check must still exercise all routes'
    foreach ($call in $latePublicCalls) {
        Assert-True ($call.timeout -gt 0 -and $call.timeout -le 7) 'HTTP timeouts must shrink to the remaining forward budget'
    }
    $global:edugraphTestElapsedSeconds = 0

    foreach ($case in @(
        @('docker.io/cloudflare/cloudflared:2026.1', 'cloudflare/cloudflared'),
        @('cloudflare/cloudflared:2026.1', 'docker.io/cloudflare/cloudflared'),
        @('docker.io/alpine:3', 'alpine'),
        @('alpine:3', 'docker.io/library/alpine'),
        @('index.docker.io/cloudflare/cloudflared:2026.1', 'cloudflare/cloudflared'),
        @('registry.example:5000/cloudflare/cloudflared:2026.1', 'registry.example:5000/cloudflare/cloudflared')
    )) {
        $global:edugraphTestConnectorImage = $case[0]
        $global:edugraphTestConnectorDigestRepository = $case[1]
        Invoke-TestDeployment -EnvFile $testEnv -BackendTag $third -FrontendTag $third -Tunnel
        $normalizedState = Get-Content $statePath -Raw | ConvertFrom-Json
        Assert-True ($normalizedState.images.cloudflared.StartsWith("$($case[1])@sha256:", [StringComparison]::Ordinal)) 'equivalent repository names must retain the exact inspected digest'
    }
    $global:edugraphTestConnectorImage = 'docker.io/cloudflare/cloudflared:2026.1'
    foreach ($unrelated in @('ghcr.io/cloudflare/cloudflared', 'cloudflare/cloudflared-other', 'other/cloudflared', 'registry.example:5000/cloudflare/cloudflared')) {
        $global:edugraphTestConnectorDigestRepository = $unrelated
        $beforeMismatch = Get-Content $statePath -Raw
        $beforeUpCount = @($global:edugraphTestCalls | Where-Object { $_.arguments -contains 'up' }).Count
        $mismatchFailed = $false
        try { Invoke-TestDeployment -EnvFile $testEnv -BackendTag $third -FrontendTag $third -Tunnel } catch {
            Assert-True ($_.Exception.Message -like 'No repository digest found*') 'unrelated repositories must fail digest matching'
            $mismatchFailed = $true
        }
        Assert-True $mismatchFailed 'normalization must not accept a different registry or repository'
        Assert-True ((Get-Content $statePath -Raw) -eq $beforeMismatch) 'a repository mismatch must preserve release state'
        Assert-True (@($global:edugraphTestCalls | Where-Object { $_.arguments -contains 'up' }).Count -eq $beforeUpCount) 'a repository mismatch must fail before activation'
    }
    $global:edugraphTestConnectorImage = $null
    $global:edugraphTestConnectorDigestRepository = $null

    $cachedBaseline = Get-Content $statePath -Raw
    $cachedImages = (Get-Content $statePath -Raw | ConvertFrom-Json -AsHashtable).images
    foreach ($cacheMode in @('all-cached-registry-down', 'one-missing', 'missing-registry-down')) {
        $global:edugraphTestRecoveryRegistryDown = $cacheMode -ne 'one-missing'
        $missingReference = @($cachedImages.Values)[0]
        if ($cacheMode -ne 'all-cached-registry-down') { $null = $global:edugraphTestMissingImages.Add($missingReference) }
        $global:edugraphTestFailNextUp = $true
        $callStart = $global:edugraphTestCalls.Count
        $processStart = $global:edugraphTestProcessCalls.Count
        $recoveryFailed = $false
        try { Invoke-TestDeployment -EnvFile $testEnv -BackendTag $first -FrontendTag $second } catch {
            Assert-True ($_.Exception.Message -eq 'Docker Compose failed: up') 'cache-aware recovery must preserve the original deployment error'
            if ($cacheMode -eq 'missing-registry-down') {
                Assert-True ($_.Exception.Data['RollbackFailure'] -like 'Cannot pull missing rollback image:*') 'an uncached image with unavailable registry must report recovery failure'
            } else {
                Assert-True (-not $_.Exception.Data.Contains('RollbackFailure')) 'cached or successfully fetched digests must restore the old release'
            }
            $recoveryFailed = $true
        }
        Assert-True $recoveryFailed 'a failed release must fail even after cache-aware rollback'
        $recoveryCalls = @($global:edugraphTestProcessCalls | Select-Object -Skip $processStart | Where-Object recovering)
        $recoveryPulls = @($recoveryCalls | Where-Object { $_.arguments -contains 'pull' })
        if ($cacheMode -eq 'all-cached-registry-down') {
            Assert-True ($recoveryPulls.Count -eq 0) 'fully cached recovery must not contact the registry'
        } else {
            Assert-True ($recoveryPulls.Count -eq 1 -and $recoveryPulls[0].arguments[-1] -eq $missingReference) 'recovery must pull only the missing saved digest'
        }
        Assert-True ((Get-Content $statePath -Raw) -eq $cachedBaseline) 'cache-aware rollback must preserve release metadata'
        if ($cacheMode -ne 'missing-registry-down') { Assert-TestRuntime $cachedImages }
        else { Assert-RecoveryOverrides @($global:edugraphTestCalls | Select-Object -Skip $callStart) 1 }
        $global:edugraphTestRecoveryRegistryDown = $false
        $global:edugraphTestMissingImages.Clear()
    }

    Set-Alias -Name Remove-Item -Value Invoke-TestCleanup -Scope Script
    try {
        foreach ($outcome in @('success', 'activation-failed')) {
            $global:edugraphTestCleanupAttempts = [Collections.Generic.List[string]]::new()
            $global:edugraphTestCleanupBlocked = [Collections.Generic.List[string]]::new()
            $global:edugraphTestLockOverrideCleanup = $true
            $global:edugraphTestLockMetadataCleanup = $true
            $beforeCleanup = Get-Content $statePath -Raw
            $global:edugraphTestFailNextUp = $outcome -eq 'activation-failed'
            $cleanupWarnings = @()
            $cleanupDeployFailed = $false
            try { Invoke-TestDeployment -EnvFile $testEnv -BackendTag $first -FrontendTag $second -WarningVariable cleanupWarnings } catch {
                Assert-True ($_.Exception.Message -eq 'Docker Compose failed: up') 'cleanup failure must not replace the deployment error'
                $cleanupDeployFailed = $true
            }
            Assert-True ($cleanupDeployFailed -eq ($outcome -eq 'activation-failed')) 'temporary file deletion must not change the deployment result'
            Assert-True ($global:edugraphTestCleanupBlocked.Count -eq 2) 'both override and metadata deletion failures must be exercised'
            foreach ($path in $global:edugraphTestCleanupBlocked) {
                Assert-True (@($cleanupWarnings | Where-Object { [string]$_ -like '*Cannot remove deployment temporary file*' -and ([string]$_).Contains($path) }).Count -eq 1) 'each cleanup failure must warn with its exact path'
                Assert-True (Test-Path -LiteralPath $path) 'locked temporary files must remain available for later cleanup'
                $null = $preservedOverrides.Add($path)
            }
            $cleaned = @($global:edugraphTestCleanupAttempts | Where-Object { -not $global:edugraphTestCleanupBlocked.Contains($_) })
            Assert-True ($cleaned.Count -gt 0) 'cleanup must continue after a locked file'
            foreach ($path in $cleaned) { Assert-True (-not (Test-Path -LiteralPath $path)) 'other temporary files must still be deleted' }
            if ($outcome -eq 'activation-failed') {
                Assert-True ((Get-Content $statePath -Raw) -eq $beforeCleanup) 'failed activation must retain the prior record despite cleanup errors'
            } else {
                $committed = Get-Content $statePath -Raw | ConvertFrom-Json -AsHashtable
                Assert-True ($committed.backendTag -eq $first -and $committed.frontendTag -eq $second) 'a committed release must remain recorded despite cleanup errors'
                Assert-TestRuntime $committed.images
            }
        }
    } finally { Microsoft.PowerShell.Management\Remove-Item -LiteralPath Alias:Remove-Item }

    # Use a second pwsh process and a different secrets path: the lock must be
    # project-wide, not tied to a checkout or the location of production.env.
    $probeEnv = Join-Path $testRoot 'alternate.env'
    Set-Content -LiteralPath $probeEnv -Value '# isolated lock probe'
    $marker = Join-Path $testRoot 'lock-probe-docker.txt'
    $invalidRelease = Join-Path $testRoot 'invalid-release.json'
    Set-Content -LiteralPath $invalidRelease -Value 'invalid JSON must not be read while locked'
    $holder = [Threading.Mutex]::new($false, 'Global\edugraph-production')
    $holderAcquired = $false
    try {
        $holderAcquired = $holder.WaitOne(0)
        Assert-True $holderAcquired 'earlier successful and failed deployments must release the project lock'
        $blocked = Invoke-DeploymentLockProbe $probeEnv $marker $invalidRelease
        Assert-True ($blocked -like 'Another edugraph deployment is already running*') 'a second process must be rejected before reading rollback state'
        Assert-True (-not (Test-Path -LiteralPath $marker)) 'a competing deployment must not reach Docker'
    } finally {
        if ($holderAcquired) { $holder.ReleaseMutex() }
        $holder.Dispose()
    }
    $unblocked = Invoke-DeploymentLockProbe $probeEnv $marker
    Assert-True ($unblocked -eq 'Probe completed') 'a second process must deploy after the project lock is released'
    Assert-True (Test-Path -LiteralPath $marker) 'unblocked validation must reach its isolated Docker mock'
    Write-Host 'Deployment orchestration tests passed.'
} finally {
    Remove-Item -LiteralPath Alias:Invoke-DeploymentWebRequest -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath Alias:Start-Sleep -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath Alias:Invoke-DeploymentProcess, Alias:Get-DeploymentElapsedSeconds -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath Alias:New-MetadataStage, Alias:Publish-ReleaseMetadata -ErrorAction SilentlyContinue
    foreach ($path in $preservedOverrides) { Remove-Item -LiteralPath $path -Force }
    # Delete only this test-created directory under the OS temp root.
    $resolvedTestRoot = [IO.Path]::GetFullPath($testRoot)
    $tempPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if ($resolvedTestRoot.StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path $resolvedTestRoot -Leaf).StartsWith('edugraph-deploy-tests-')) {
        Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
    }
}

