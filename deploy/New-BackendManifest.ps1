#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$RecordsDirectory,
    [Parameter(Mandatory)][string]$Repository,
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{40}$')][string]$Commit,
    [Parameter(Mandatory)][ValidatePattern('^[1-9][0-9]*$')][string]$RunId,
    [Parameter(Mandatory)][ValidatePattern('^[1-9][0-9]*$')][string]$Attempt,
    [Parameter(Mandatory)][string]$OutputFile
)
$ErrorActionPreference = 'Stop'
$required = @('core-service', 'gateway-service', 'edugraph-ai-core', 'edugraph-ai-core-gpu')
$records = @(Get-ChildItem -LiteralPath $RecordsDirectory -Filter image-release.json -Recurse -File)
if ($records.Count -ne $required.Count) { throw 'A complete backend image set from this run attempt is required; rerun all jobs.' }
$images = @{}
$owner = $Repository.Split('/')[0].ToLowerInvariant()
foreach ($file in $records) {
    $record = Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json -AsHashtable
    if ($record.repository -cne $Repository -or $record.commit -cne $Commit -or
        [string]$record.runId -cne $RunId -or [string]$record.attempt -cne $Attempt) {
        throw 'Image records must come from the selected repository, commit, run and attempt.'
    }
    $component = [string]$record.component
    if ($component -notin $required -or $images.ContainsKey($component)) { throw 'Unexpected or duplicate backend component.' }
    if ($record.image -isnot [string] -or $record.digest -isnot [string] -or
        $record.image -cne "ghcr.io/$owner/edugraph/$component" -or $record.digest -cnotmatch '^sha256:[a-f0-9]{64}$') {
        throw 'Invalid published backend image reference.'
    }
    $images[$component] = "$($record.image)@$($record.digest)"
}
$manifest = @{
    schemaVersion = 1; kind = 'backend'; repository = $Repository
    commit = $Commit; tag = "sha-$Commit"; runId = $RunId; attempt = $Attempt
    images = $images
}
$manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $OutputFile -Encoding utf8
