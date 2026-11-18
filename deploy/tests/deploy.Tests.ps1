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
$global:edugraphTestFailPull = $false
$global:edugraphTestBadOrigin = $false

function docker {
    $global:edugraphTestCalls.Add(@{ arguments = @($args); backend = $env:BACKEND_TAG; frontend = $env:FRONTEND_TAG })
    $global:LASTEXITCODE = 0
    if ($args -contains '--format') {
        return '{"services":{"core-service":{"image":"ghcr.io/test/core","environment":{"JWT_SECRET":"dGVzdC1vbmx5LWp3dC1zZWNyZXQtdGhhdC1pcy1hdC1sZWFzdC0zMi1ieXRlcw=="}},"cloudflared":{"image":"cloudflare/cloudflared:test","environment":{"TUNNEL_TOKEN":"test-token"}}}}'
    }
    if ($global:edugraphTestFailPull -and $args -contains 'pull') { $global:LASTEXITCODE = 1 }
    if ($global:edugraphTestFailNextUp -and $args -contains 'up') {
        $global:edugraphTestFailNextUp = $false
        $global:LASTEXITCODE = 1
    }
}
function Invoke-WebRequest {
    param([string]$Uri, [int]$TimeoutSec, [switch]$SkipHttpErrorCheck)
    if ($global:edugraphTestBadOrigin) { throw 'Simulated origin failure' }
    @{ StatusCode = $(if ($Uri.EndsWith('/api/users/me')) { 401 } else { 200 }); Content = '<html></html>' }
}
function Assert-True($Condition, [string]$Message) {
    if (-not $Condition) { throw "Assertion failed: $Message" }
}

try {
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
    Assert-True ($env:BACKEND_TAG -eq 'original-value') 'caller environment must be restored'
    $up = @($global:edugraphTestCalls | Where-Object { $_.arguments -contains 'up' })[-1]
    Assert-True (($up.arguments -join ' ') -match 'compose.gpu.yml') 'GPU override must be used'

    $global:edugraphTestFailNextUp = $true
    $failed = $false
    try { & $deployScript -EnvFile $testEnv -BackendTag $third -FrontendTag $third } catch { $failed = $true }
    Assert-True $failed 'a failed release must fail the job even after successful rollback'
    $up = @($global:edugraphTestCalls | Where-Object { $_.arguments -contains 'up' })[-1]
    Assert-True ($up.backend -eq $first -and $up.frontend -eq $second) 'rollback must use the last successful tags'
    Assert-True (($up.arguments -join ' ') -match 'compose.gpu.yml.*--profile tunnel') 'rollback must restore GPU/tunnel modes'
    $state = Get-Content $statePath -Raw | ConvertFrom-Json
    Assert-True ($state.backendTag -eq $first) 'failed release must not overwrite state'

    $upCount = @($global:edugraphTestCalls | Where-Object { $_.arguments -contains 'up' }).Count
    $global:edugraphTestFailPull = $true
    try { & $deployScript -EnvFile $testEnv -BackendTag $third -FrontendTag $third } catch { }
    Assert-True (@($global:edugraphTestCalls | Where-Object { $_.arguments -contains 'up' }).Count -eq $upCount) 'failed pulls must not replace containers'
    $global:edugraphTestFailPull = $false

    & $deployScript -EnvFile $testEnv -BackendTag $third -FrontendTag $third
    $previous = Get-Content (Join-Path $testRoot 'previous-release.json') -Raw | ConvertFrom-Json
    Assert-True ($previous.backendTag -eq $first) 'successful update must retain rollback state'
    Write-Host 'Deployment orchestration tests passed.'
} finally {
    # Delete only this test-created directory under the OS temp root.
    $resolvedTestRoot = [IO.Path]::GetFullPath($testRoot)
    $tempPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if ($resolvedTestRoot.StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path $resolvedTestRoot -Leaf).StartsWith('edugraph-deploy-tests-')) {
        Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
    }
}

