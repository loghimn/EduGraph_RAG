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
$global:edugraphTestBadOrigin = $false
$global:edugraphTestTagGeneration = 'original'
$global:edugraphTestRunningImages = @{}
$global:edugraphTestNoDigest = $false
$global:edugraphTestWrongImage = $false
$global:edugraphTestFailNextStop = $false
$global:edugraphTestIgnoreNextStop = $false
$global:edugraphTestCheckLockAtOrigin = $true
$global:edugraphTestLockCheckCount = 0
$global:edugraphTestHttpCalls = [Collections.Generic.List[object]]::new()
$global:edugraphTestPublicFailures = 0
$global:edugraphTestPublicWrongAssets = 0
$global:edugraphTestPublicStatus = 503
$global:edugraphTestRetryDelays = 0
$preservedOverrides = [Collections.Generic.HashSet[string]]::new()
$global:edugraphTestFailMetadataStage = $false
$global:edugraphTestPublicationFailures = [Collections.Generic.Queue[string]]::new()
$global:edugraphTestFailRollbackOnPublish = $false
$global:edugraphTestCurrentStage = $null
$global:edugraphTestCheckStageAtUp = $false

function Get-TestImageId([string]$Reference) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($Reference)
    return 'sha256:' + [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
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
    if ($args[0] -eq 'image') {
        $reference = [string]$args[-1]
        if ($args -contains '{{json .RepoDigests}}') {
            if ($global:edugraphTestNoDigest) { return '[]' }
            $repository = $reference -replace ':[^/:]+$', ''
            $digest = Get-TestImageId "$reference-$global:edugraphTestTagGeneration"
            return ConvertTo-Json -InputObject @("$repository@$digest") -Compress
        }
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
        return $configuration | ConvertTo-Json -Depth 5
    }
    if ($global:edugraphTestFailPull -and $args -contains 'pull') { $global:LASTEXITCODE = 1 }
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
    $global:edugraphTestHttpCalls.Add(@{ uri = $Uri; public = $public; path = $path; timeout = $TimeoutSec; redirects = $MaximumRedirection; headers = $Headers })
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

function Assert-RecoveryOverrides($Calls) {
    $paths = @($Calls.arguments | Where-Object { [IO.Path]::GetExtension([string]$_) -eq '.tmp' } | Select-Object -Unique)
    Assert-True ($paths.Count -ge 2) 'both attempted and rollback image overrides must remain available'
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

function Invoke-DeploymentLockProbe([string]$ProbeEnv, [string]$Marker, [string]$ProbeRelease = '') {
    $job = Start-Job -ScriptBlock {
        param($DeployScript, $EnvFile, $MarkerPath, $ReleasePath)
        $ErrorActionPreference = 'Stop'
        function docker {
            Set-Content -LiteralPath $MarkerPath -Value 'Docker called'
            $global:LASTEXITCODE = 0
        }
        $parameters = @{
            EnvFile = $EnvFile; BackendTag = 'sha-' + ('a' * 40)
            FrontendTag = 'sha-' + ('b' * 40); ValidateOnly = $true
        }
        if ($ReleasePath) { $parameters.ReleaseFile = $ReleasePath }
        try {
            & $DeployScript @parameters
            'Probe completed'
        } catch { $_.Exception.Message }
    } -ArgumentList $deployScript, $ProbeEnv, $Marker, $ProbeRelease
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
    Set-Alias -Name Invoke-WebRequest -Value Invoke-TestWebRequest -Scope Script
    Set-Alias -Name Start-Sleep -Value Invoke-TestRetryDelay -Scope Script
    $first = 'sha-' + ('a' * 40)
    $second = 'sha-' + ('b' * 40)
    $third = 'sha-' + ('c' * 40)
    & $deployScript -EnvFile $testEnv -BackendTag $first -FrontendTag $first -ValidateOnly
    Assert-True ($global:edugraphTestCalls.Count -eq 1) 'ValidateOnly must not pull or deploy'

    $env:BACKEND_TAG = 'original-value'
    & $deployScript -EnvFile $testEnv -BackendTag $first -FrontendTag $second -Gpu -Tunnel
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
    try { & $deployScript -EnvFile $testEnv -BackendTag $third -FrontendTag $third } catch {
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
    try { & $deployScript -EnvFile $testEnv -BackendTag $third -FrontendTag $third } catch {
        Assert-True ($_.Exception.Message -eq 'Docker Compose failed: pull') 'pull must report its original error'
        $pullFailed = $true
    }
    Assert-True $pullFailed 'a failed pull must fail the job'
    Assert-True (@($global:edugraphTestCalls | Where-Object { $_.arguments -contains 'up' }).Count -eq $upCount) 'failed pulls must not replace containers'
    $global:edugraphTestFailPull = $false

    $global:edugraphTestNoDigest = $true
    $digestFailed = $false
    try { & $deployScript -EnvFile $testEnv -BackendTag $third -FrontendTag $third } catch {
        Assert-True ($_.Exception.Message -like 'No repository digest found*') 'missing digest must report its cause'
        $digestFailed = $true
    }
    $global:edugraphTestNoDigest = $false
    Assert-True $digestFailed 'images without digests must not deploy'
    Assert-True (@($global:edugraphTestCalls | Where-Object { $_.arguments -contains 'up' }).Count -eq $upCount) 'digest resolution must finish before replacing containers'

    $global:edugraphTestWrongImage = $true
    $imageFailed = $false
    try { & $deployScript -EnvFile $testEnv -BackendTag $third -FrontendTag $third } catch {
        Assert-True ($_.Exception.Message -like 'Running image differs*') 'unexpected running image must report its cause'
        $imageFailed = $true
    }
    Assert-True $imageFailed 'a healthy container with the wrong image must not become a successful release'

    & $deployScript -EnvFile $testEnv -BackendTag $third -FrontendTag $third
    $previous = Get-Content (Join-Path $testRoot 'previous-release.json') -Raw | ConvertFrom-Json
    Assert-True ($previous.backendTag -eq $first) 'successful update must retain rollback state'
    Assert-True (($previous.images | ConvertTo-Json -Compress) -eq $originalImages) 'previous release must retain exact tested digests'
    $offState = Get-Content $statePath -Raw | ConvertFrom-Json
    Assert-True (-not $offState.tunnel) 'tunnel-off release must record its target mode'
    Assert-True (-not $global:edugraphTestRunningImages.ContainsKey('cloudflared')) 'on-to-off deployment must explicitly stop the existing connector'
    $offReleasePath = Join-Path $testRoot 'saved-tunnel-off-release.json'
    Copy-Item -LiteralPath $statePath -Destination $offReleasePath

    $callCount = $global:edugraphTestCalls.Count
    & $deployScript -EnvFile $testEnv -ReleaseFile (Join-Path $testRoot 'previous-release.json')
    $manualCalls = @($global:edugraphTestCalls | Select-Object -Skip $callCount)
    Assert-True (@($manualCalls | Where-Object { $_.arguments -contains '{{json .RepoDigests}}' }).Count -eq 0) 'manual rollback must not resolve mutable tags'
    $state = Get-Content $statePath -Raw | ConvertFrom-Json
    Assert-True (($state.images | ConvertTo-Json -Compress) -eq $originalImages) 'manual rollback must restore exact saved digests'
    Assert-True ($state.gpu -and $state.tunnel) 'manual rollback must restore saved modes'
    Assert-True ($global:edugraphTestRunningImages.ContainsKey('cloudflared')) 'off-to-on rollback must restart the connector through up'

    & $deployScript -EnvFile $testEnv -ReleaseFile $offReleasePath
    Assert-True (-not $global:edugraphTestRunningImages.ContainsKey('cloudflared')) 'manual rollback to tunnel-off must stop the connector'
    $offStateJson = Get-Content $statePath -Raw
    $global:edugraphTestFailNextUp = $true
    $tunnelFailed = $false
    try { & $deployScript -EnvFile $testEnv -BackendTag $first -FrontendTag $second -Tunnel } catch {
        Assert-True ($_.Exception.Message -eq 'Docker Compose failed: up') 'failed tunnel-on release must preserve its original error'
        $tunnelFailed = $true
    }
    Assert-True $tunnelFailed 'partially started tunnel-on release must fail'
    Assert-True (-not $global:edugraphTestRunningImages.ContainsKey('cloudflared')) 'automatic rollback to tunnel-off must stop a partially started connector'
    Assert-True ((Get-Content $statePath -Raw) -eq $offStateJson) 'failed tunnel-on release must preserve the tunnel-off state'

    $global:edugraphTestFailNextStop = $true
    $stopFailed = $false
    try { & $deployScript -EnvFile $testEnv -BackendTag $first -FrontendTag $second } catch {
        Assert-True ($_.Exception.Message -eq 'Cannot stop project Cloudflare connector.') 'connector stop failure must remain visible'
        $stopFailed = $true
    }
    Assert-True $stopFailed 'failed connector stop must fail deployment'
    Assert-True ((Get-Content $statePath -Raw) -eq $offStateJson) 'failed connector stop must not persist a new release'

    $global:edugraphTestRunningImages['cloudflared'] = 'stale-connector'
    $global:edugraphTestIgnoreNextStop = $true
    $stillRunningFailed = $false
    try { & $deployScript -EnvFile $testEnv -BackendTag $first -FrontendTag $second } catch {
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
    & $deployScript -EnvFile $testEnv -BackendTag $third -FrontendTag $third
    Assert-True (@($global:edugraphTestHttpCalls | Where-Object public).Count -eq $publicCount) 'tunnel-off mode must use loopback checks only'
    Assert-True ($global:edugraphTestPublicFailures -eq 4) 'tunnel-off mode must not depend on public connectivity'

    $global:edugraphTestPublicFailures = 2
    $delayCount = $global:edugraphTestRetryDelays
    & $deployScript -EnvFile $testEnv -BackendTag $first -FrontendTag $second -Tunnel
    Assert-True ($global:edugraphTestRetryDelays -eq $delayCount + 2) 'transient public failures must retry before accepting the release'
    $publicState = Get-Content $statePath -Raw
    $httpCount = $global:edugraphTestHttpCalls.Count
    $global:edugraphTestPublicFailures = 4
    $publicFailed = $false
    try { & $deployScript -EnvFile $testEnv -BackendTag $third -FrontendTag $third -Tunnel } catch {
        Assert-True ($_.Exception.Message -like 'Public route check failed*after 4 attempts*') 'unavailable public route must report bounded failure'
        $publicFailed = $true
    }
    Assert-True $publicFailed 'loopback success must not accept an unavailable public route'
    Assert-True ((Get-Content $statePath -Raw) -eq $publicState) 'public failure must not overwrite the last successful release'
    $publicCalls = @($global:edugraphTestHttpCalls | Select-Object -Skip $httpCount | Where-Object public)
    Assert-True ($publicCalls.Count -eq 7) 'four failed attempts must be followed by all three public rollback probes'
    Assert-True ($publicCalls[-1].path -eq '/api/users/me') 'tunnel-enabled rollback must verify its protected public API'

    $global:edugraphTestPublicWrongAssets = 4
    $wrongUpstreamFailed = $false
    try { & $deployScript -EnvFile $testEnv -BackendTag $third -FrontendTag $third -Tunnel } catch {
        Assert-True ($_.Exception.Message -like '*deployed frontend assets*') 'wrong frontend upstream must explain the asset mismatch'
        $wrongUpstreamFailed = $true
    }
    Assert-True $wrongUpstreamFailed 'public HTTP 200 alone must not prove the correct frontend is served'
    Assert-True ((Get-Content $statePath -Raw) -eq $publicState) 'wrong public upstream must not become the current release'

    $global:edugraphTestPublicStatus = 302
    $global:edugraphTestPublicFailures = 4
    $redirectFailed = $false
    try { & $deployScript -EnvFile $testEnv -BackendTag $third -FrontendTag $third -Tunnel } catch {
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
    try { & $deployScript -EnvFile $testEnv -BackendTag $third -FrontendTag $third -WarningVariable rollbackWarnings } catch {
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
    try { & $deployScript -EnvFile $testEnv -BackendTag $third -FrontendTag $third } catch {
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
        Assert-True ($call.redirects -eq 0) 'public checks must not follow redirects'
        Assert-True ($call.headers['Cache-Control'] -eq 'no-cache') 'public checks must bypass cached responses'
    }

    # Publication faults happen after healthy activation, and must reconcile both
    # runtime and release records. Use real file renames except at injected faults.
    & $deployScript -EnvFile $testEnv -BackendTag $first -FrontendTag $second -Tunnel
    $baselineCurrent = Get-Content $statePath -Raw
    $baselinePrevious = Get-Content (Join-Path $testRoot 'previous-release.json') -Raw
    $baselineImages = ($baselineCurrent | ConvertFrom-Json -AsHashtable).images
    Set-Alias -Name New-MetadataStage -Value Invoke-TestMetadataStage -Scope Script
    Set-Alias -Name Publish-ReleaseMetadata -Value Invoke-TestMetadataPublish -Scope Script

    $global:edugraphTestFailMetadataStage = $true
    $upCount = @($global:edugraphTestCalls | Where-Object { $_.arguments -contains 'up' }).Count
    $stageFailed = $false
    try { & $deployScript -EnvFile $testEnv -BackendTag $third -FrontendTag $third } catch {
        Assert-True ($_.Exception.Message -eq 'Simulated metadata staging failure') 'staging failure must report its cause'
        $stageFailed = $true
    }
    Assert-True $stageFailed 'failed metadata staging must fail deployment'
    Assert-True (@($global:edugraphTestCalls | Where-Object { $_.arguments -contains 'up' }).Count -eq $upCount) 'staging failure must happen before changing containers'
    Assert-TestRuntime $baselineImages

    foreach ($destination in @('previous-release.json', 'current-release.json')) {
        $global:edugraphTestPublicationFailures.Enqueue($destination)
        $publicationFailed = $false
        try { & $deployScript -EnvFile $testEnv -BackendTag $third -FrontendTag $third } catch {
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
    try { & $deployScript -EnvFile $testEnv -BackendTag $third -FrontendTag $third -WarningVariable publicationWarnings } catch {
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
    try { & $deployScript -EnvFile $testEnv -BackendTag $third -FrontendTag $third -WarningVariable metadataWarnings } catch {
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
    try { & $deployScript -EnvFile $firstEnv -BackendTag $third -FrontendTag $third } catch {
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
    try { & $deployScript -EnvFile $testEnv -ReleaseFile $legacyPath } catch {
        Assert-True ($_.Exception.Message -like 'Release must contain immutable*') 'tag-only state must explain why rollback is unsafe'
        $legacyFailed = $true
    }
    Assert-True $legacyFailed 'legacy tag-only releases must not silently fall back to mutable tags'

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
    Remove-Item -LiteralPath Alias:Invoke-WebRequest -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath Alias:Start-Sleep -ErrorAction SilentlyContinue
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

