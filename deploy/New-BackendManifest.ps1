#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$RecordsDirectory,
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$')][string]$Repository,
    [Parameter(Mandatory)][ValidatePattern('(?-i)^[a-f0-9]{40}$')][string]$Commit,
    [Parameter(Mandatory)][ValidatePattern('^[1-9][0-9]*$')][string]$RunId,
    [Parameter(Mandatory)][ValidatePattern('^[1-9][0-9]*$')][string]$Attempt,
    [Parameter(Mandatory)][string]$OutputFile
)
$ErrorActionPreference = 'Stop'
$required = @('core-service', 'gateway-service', 'edugraph-ai-core', 'edugraph-ai-core-gpu')
$records = @(Get-ChildItem -LiteralPath $RecordsDirectory -Filter image-release.json -Recurse -File)
if ($records.Count -lt $required.Count) { throw 'A complete backend image set from this workflow run is required.' }
$images = @{}
$componentAttempts = @{}
$seen = @{}
$owner = $Repository.Split('/')[0].ToLowerInvariant()
foreach ($file in $records) {
    $record = Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json -AsHashtable -NoEnumerate
    if ($record -isnot [System.Collections.IDictionary] -or
        $record.repository -isnot [string] -or $record.commit -isnot [string] -or
        $record.runId -isnot [string] -or $record.attempt -isnot [string] -or
        $record.repository -cne $Repository -or $record.commit -cne $Commit -or
        $record.runId -cne $RunId -or $record.attempt -cnotmatch '^[1-9][0-9]*$' -or
        [bigint]$record.attempt -gt [bigint]$Attempt) {
        throw 'Image records must come from the selected repository, commit, run and attempt.'
    }
    $component = $record.component
    if ($component -isnot [string] -or $component -cnotin $required -or $seen.ContainsKey("${component}:$($record.attempt)")) {
        throw 'Unexpected or duplicate backend component.'
    }
    $seen["${component}:$($record.attempt)"] = $true
    if ($record.image -isnot [string] -or $record.digest -isnot [string] -or
        $record.image -cne "ghcr.io/$owner/edugraph/$component" -or $record.digest -cnotmatch '^sha256:[a-f0-9]{64}$') {
        throw 'Invalid published backend image reference.'
    }
    if (-not $componentAttempts.ContainsKey($component) -or
        [bigint]$record.attempt -gt [bigint]$componentAttempts[$component]) {
        $images[$component] = "$($record.image)@$($record.digest)"
        $componentAttempts[$component] = $record.attempt
    }
}
if ($images.Count -ne $required.Count) { throw 'A complete backend image set from this workflow run is required.' }
$manifest = @{
    schemaVersion = 1; kind = 'backend'; repository = $Repository
    commit = $Commit; tag = "sha-$Commit"; runId = $RunId; attempt = $Attempt
    images = $images; componentAttempts = $componentAttempts
}
$manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $OutputFile -Encoding utf8
