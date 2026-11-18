#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$scriptPath = Join-Path $PSScriptRoot '../New-BackendManifest.ps1'
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('edugraph-manifest-tests-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $testRoot | Out-Null
$parameters = @{
    RecordsDirectory = $testRoot; Repository = 'test/backend'; Commit = 'a' * 40
    RunId = '123'; Attempt = '2'; OutputFile = Join-Path $testRoot 'backend-release.json'
}
$components = @('core-service', 'gateway-service', 'edugraph-ai-core', 'edugraph-ai-core-gpu')
function Write-TestRecord([string]$Directory, [string]$Component, [string]$Attempt = '2', [string]$RunId = '123') {
    New-Item -ItemType Directory -Path $Directory -Force | Out-Null
    @{
        repository = 'test/backend'; commit = 'a' * 40; runId = $RunId; attempt = $Attempt
        component = $Component; image = "ghcr.io/test/edugraph/$Component"; digest = 'sha256:' + ('b' * 64)
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $Directory 'image-release.json')
}
function Assert-Rejected([string]$Reason, [string]$ExpectedMessage = '') {
    $failed = $false
    try { & $scriptPath @parameters } catch {
        if ($ExpectedMessage -and $_.Exception.Message -cne $ExpectedMessage) { throw }
        $failed = $true
    }
    if (-not $failed) { throw "Manifest accepted invalid build set: $Reason" }
}
try {
    Assert-Rejected 'no images'
    foreach ($component in $components[0..2]) { Write-TestRecord (Join-Path $testRoot $component) $component }
    Assert-Rejected 'missing GPU image'
    $gpuDirectory = Join-Path $testRoot 'edugraph-ai-core-gpu'
    Write-TestRecord $gpuDirectory 'edugraph-ai-core-gpu' '1'
    Assert-Rejected 'mixed run attempts'
    Write-TestRecord $gpuDirectory 'edugraph-ai-core-gpu' '2' '456'
    Assert-Rejected 'mixed workflow runs'
    Write-TestRecord $gpuDirectory 'core-service'
    Assert-Rejected 'duplicate component'
    Write-TestRecord $gpuDirectory 'unexpected-service'
    Assert-Rejected 'unexpected component'
    foreach ($field in @('image', 'digest')) {
        $valid = if ($field -eq 'image') { 'ghcr.io/test/edugraph/edugraph-ai-core-gpu' } else { 'sha256:' + ('b' * 64) }
        foreach ($case in @(
            @{ name = 'array of two valid strings'; value = @($valid, $valid) },
            @{ name = 'array of one valid string'; value = @($valid) },
            @{ name = 'empty array'; value = @() },
            @{ name = 'object'; value = @{ reference = $valid } },
            @{ name = 'null'; value = $null },
            @{ name = 'number'; value = 42 }
        )) {
            Write-TestRecord $gpuDirectory 'edugraph-ai-core-gpu'
            $recordPath = Join-Path $gpuDirectory 'image-release.json'
            $record = Get-Content $recordPath -Raw | ConvertFrom-Json -AsHashtable
            $record[$field] = $case.value
            $record | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $recordPath
            Assert-Rejected "$field is $($case.name)" 'Invalid published backend image reference.'
            if (Test-Path -LiteralPath $parameters.OutputFile) { throw 'Invalid records must not publish a manifest.' }
        }
    }
    Write-TestRecord $gpuDirectory 'edugraph-ai-core-gpu'
    & $scriptPath @parameters
    $manifest = Get-Content $parameters.OutputFile -Raw | ConvertFrom-Json -AsHashtable
    if ($manifest.images.Count -ne 4 -or $manifest.runId -ne '123' -or $manifest.attempt -ne '2' -or $manifest.tag -ne ('sha-' + ('a' * 40))) {
        throw 'Complete manifest must preserve the selected build and all four image digests.'
    }
    foreach ($component in $components) {
        if ($manifest.images[$component] -ne "ghcr.io/test/edugraph/$component@sha256:$('b' * 64)") { throw 'Published digest was changed.' }
    }
    Write-Host 'Complete CI manifest tests passed.'
} finally {
    $resolved = [IO.Path]::GetFullPath($testRoot)
    $prefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if ($resolved.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase) -and (Split-Path $resolved -Leaf).StartsWith('edugraph-manifest-tests-')) {
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
