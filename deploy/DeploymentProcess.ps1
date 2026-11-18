function Invoke-DeploymentProcess {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [string[]]$Arguments = @(),
        [Parameter(Mandatory)][ValidateRange(1, 3300)][int]$TimeoutSeconds
    )
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $FilePath
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    foreach ($argument in $Arguments) { $startInfo.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    try {
        if (-not $process.Start()) { throw 'Cannot start deployment command.' }
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            $process.Kill($true)
            if (-not $process.WaitForExit(5000)) { throw [TimeoutException]::new('Timed-out deployment command could not be stopped.') }
            throw [TimeoutException]::new("Deployment command exceeded its $TimeoutSeconds-second budget.")
        }
        $reads = [Threading.Tasks.Task[]]@($stdout, $stderr)
        if (-not [Threading.Tasks.Task]::WaitAll($reads, 5000)) { throw [TimeoutException]::new('Timed out collecting deployment command output.') }
        return @{ ExitCode = $process.ExitCode; Output = $stdout.Result; Error = $stderr.Result }
    } finally { $process.Dispose() }
}
