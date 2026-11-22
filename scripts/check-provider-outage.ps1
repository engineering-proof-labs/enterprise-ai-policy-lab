param(
    [string]$BaseUrl = 'http://127.0.0.1:18080',
    [string]$IdentityUrl = 'http://127.0.0.1:18081'
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/lab-api.ps1"
$question = 'Where do I route billing tickets?'
$token = Wait-LabToken $IdentityUrl 'analyst'
try {
    docker compose stop ollama | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'Could not stop the local fixture provider.' }
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $result = Invoke-LabQuestion $BaseUrl $token $question
    $timer.Stop()
    if ($result.HttpStatus -ne 503 -or $result.Body.code -ne 'model_unavailable' -or
        !$result.RequestId -or $timer.Elapsed.TotalSeconds -gt 35) {
        throw 'Provider outage did not return the expected bounded 503 with a request ID.'
    }
    $logs = @(docker compose logs --no-color app | Select-String -SimpleMatch "requestId=$($result.RequestId) ")
    if ($logs.Count -lt 3 -or ($logs -join "`n") -notmatch 'generationStatus=model_unavailable' -or
        ($logs -join "`n") -match ([regex]::Escape($question) + '|Billing Operations queue|Bearer ')) {
        throw 'The technical log is missing or contains fixture content.'
    }
    [pscustomobject]@{
        requestId = $result.RequestId
        http = $result.HttpStatus
        code = $result.Body.code
        elapsedMs = $timer.ElapsedMilliseconds
        technicalLog = @($logs | ForEach-Object Line)
    } | ConvertTo-Json -Depth 4
} finally {
    docker compose --ansi never up -d --wait ollama | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'Could not restore the local fixture provider.' }
}
