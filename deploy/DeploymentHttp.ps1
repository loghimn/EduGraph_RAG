# A single wall-clock budget covers connection, headers and the entire response body.
function Invoke-DeploymentWebRequest {
    param(
        [Parameter(Mandatory)][string]$Uri,
        [Parameter(Mandatory)][ValidateRange(1, 300)][int]$TimeoutSec,
        [switch]$SkipHttpErrorCheck,
        [int]$MaximumRedirection = 50,
        [hashtable]$Headers = @{}
    )
    $handler = [Net.Http.HttpClientHandler]::new()
    if (([uri]$Uri).IsLoopback) { $handler.UseProxy = $false }
    $handler.AllowAutoRedirect = $MaximumRedirection -gt 0
    if ($MaximumRedirection -gt 0) { $handler.MaxAutomaticRedirections = $MaximumRedirection }
    $client = [Net.Http.HttpClient]::new($handler)
    $client.Timeout = [Threading.Timeout]::InfiniteTimeSpan
    $cancellation = [Threading.CancellationTokenSource]::new()
    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Get, $Uri)
    $response = $null
    try {
        foreach ($name in $Headers.Keys) { $null = $request.Headers.TryAddWithoutValidation($name, [string]$Headers[$name]) }
        $cancellation.CancelAfter([TimeSpan]::FromSeconds($TimeoutSec))
        $task = $client.SendAsync($request, [Net.Http.HttpCompletionOption]::ResponseContentRead, $cancellation.Token)
        try {
            # Wait is bounded even if a transport delays observing cancellation.
            if (-not $task.Wait($TimeoutSec * 1000)) {
                $cancellation.Cancel()
                throw [TimeoutException]::new('Smoke request exceeded its full-response deadline.')
            }
            $response = $task.GetAwaiter().GetResult()
        } catch {
            if ($cancellation.IsCancellationRequested) {
                throw [TimeoutException]::new('Smoke request exceeded its full-response deadline.', $_.Exception)
            }
            throw
        }
        if (-not $SkipHttpErrorCheck) { $null = $response.EnsureSuccessStatusCode() }
        return @{
            StatusCode = [int]$response.StatusCode
            Content = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        }
    } finally {
        if ($null -ne $response) { $response.Dispose() }
        $request.Dispose()
        $cancellation.Dispose()
        $client.Dispose()
    }
}
