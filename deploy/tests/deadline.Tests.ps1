#requires -Version 7.0
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../DeploymentProcess.ps1')
. (Join-Path $PSScriptRoot '../DeploymentHttp.ps1')
$pwsh = Join-Path $PSHOME $(if ($IsWindows) { 'pwsh.exe' } else { 'pwsh' })
$result = Invoke-DeploymentProcess -FilePath $pwsh -Arguments @('-NoProfile', '-NonInteractive', '-Command', '[Console]::Out.Write("output"); [Console]::Error.Write("diagnostic"); exit 7') -TimeoutSeconds 10
if ($result.ExitCode -ne 7 -or $result.Output -ne 'output' -or $result.Error -ne 'diagnostic') {
    throw 'Bounded process runner must preserve output and failure status.'
}
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('edugraph-deadline-tests-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $testRoot | Out-Null
try {
    $childScript = Join-Path $testRoot 'slow-command.ps1'
    $pidFile = Join-Path $testRoot 'child.pid'
    @'
param([string]$PidFile)
[IO.File]::WriteAllText($PidFile, [string]$PID)
Start-Sleep -Seconds 30
'@ | Set-Content -LiteralPath $childScript
    $clock = [Diagnostics.Stopwatch]::StartNew()
    $timedOut = $false
    try {
        Invoke-DeploymentProcess -FilePath $pwsh -Arguments @('-NoProfile', '-NonInteractive', '-File', $childScript, $pidFile) -TimeoutSeconds 3
    } catch [TimeoutException] { $timedOut = $true }
    if (-not $timedOut -or $clock.Elapsed.TotalSeconds -gt 12) { throw 'A hung command must be terminated within its bounded shutdown grace.' }
    if (-not (Test-Path -LiteralPath $pidFile)) { throw 'The timeout test must start the child before terminating it.' }
    $childId = [int](Get-Content -LiteralPath $pidFile)
    $alive = $false
    try {
        $child = [Diagnostics.Process]::GetProcessById($childId)
        try { $alive = -not $child.HasExited } finally { $child.Dispose() }
    } catch [ArgumentException] { $alive = $false }
    if ($alive) { throw 'Timed-out deployment command must not remain running.' }
    foreach ($mode in @('fast', 'unauthorized', 'redirect', 'stall', 'drip')) {
        $readyFile = Join-Path $testRoot "$mode.port"
        $headersFile = Join-Path $testRoot "$mode.headers"
        $server = Start-Job -ArgumentList $readyFile, $headersFile, $mode -ScriptBlock {
            param($ReadyFile, $HeadersFile, $Mode)
            $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
            $connection = $null
            try {
                $listener.Start()
                [IO.File]::WriteAllText($ReadyFile, [string]$listener.LocalEndpoint.Port)
                $connection = $listener.AcceptTcpClient()
                $stream = $connection.GetStream()
                $reader = [IO.StreamReader]::new($stream, [Text.Encoding]::ASCII, $false, 1024, $true)
                while ($reader.ReadLine() -ne '') { }
                $status = if ($Mode -eq 'unauthorized') { '401 Unauthorized' } elseif ($Mode -eq 'redirect') { '302 Found' } else { '200 OK' }
                $body = '<html><script src="/assets/test.js"></script></html>'
                $length = if ($Mode -in @('stall', 'drip')) { 100 } else { $body.Length }
                $location = if ($Mode -eq 'redirect') { "Location: http://127.0.0.1:1/wrong`r`n" } else { '' }
                $headers = [Text.Encoding]::ASCII.GetBytes("HTTP/1.1 $status`r`nContent-Length: $length`r`nContent-Type: text/html; charset=utf-8`r`n${location}Connection: close`r`n`r`n")
                $stream.Write($headers, 0, $headers.Length)
                $stream.Flush()
                [IO.File]::WriteAllText($HeadersFile, 'sent')
                if ($Mode -eq 'stall') { Start-Sleep -Seconds 30 }
                elseif ($Mode -eq 'drip') {
                    for ($index = 0; $index -lt 100; $index++) {
                        $stream.WriteByte(65)
                        $stream.Flush()
                        Start-Sleep -Milliseconds 200
                    }
                } else {
                    $bytes = [Text.Encoding]::UTF8.GetBytes($body)
                    $stream.Write($bytes, 0, $bytes.Length)
                }
            } finally {
                if ($connection) { $connection.Dispose() }
                $listener.Stop()
            }
        }
        try {
            $startup = [Diagnostics.Stopwatch]::StartNew()
            while (-not (Test-Path -LiteralPath $readyFile)) {
                if ($startup.Elapsed.TotalSeconds -gt 10) { throw 'Smoke test server did not start.' }
                Start-Sleep -Milliseconds 50
            }
            $port = [int](Get-Content -LiteralPath $readyFile)
            $clock.Restart()
            $timedOut = $false
            try {
                $response = Invoke-DeploymentWebRequest "http://127.0.0.1:$port/courses" -TimeoutSec 1 -SkipHttpErrorCheck -MaximumRedirection 0
            } catch [TimeoutException] { $timedOut = $true }
            if ($mode -in @('stall', 'drip')) {
                if (-not $timedOut -or $clock.Elapsed.TotalSeconds -gt 4) { throw 'A stalled or dripping body must exhaust the total request budget.' }
                if (-not (Test-Path -LiteralPath $headersFile)) { throw 'The stalled body test must receive headers before timing out.' }
            } else {
                $expectedStatus = if ($mode -eq 'unauthorized') { 401 } elseif ($mode -eq 'redirect') { 302 } else { 200 }
                if ($timedOut -or $response.StatusCode -ne $expectedStatus -or $response.Content -notlike '<html>*') { throw 'Fast smoke responses must retain status and the complete body without following redirects.' }
            }
        } finally {
            Stop-Job -Job $server
            Remove-Job -Job $server -Force
        }
    }
    Write-Host 'Bounded deployment process and full-response HTTP tests passed.'
} finally {
    $resolved = [IO.Path]::GetFullPath($testRoot)
    $prefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if ($resolved.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase) -and (Split-Path $resolved -Leaf).StartsWith('edugraph-deadline-tests-')) {
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
