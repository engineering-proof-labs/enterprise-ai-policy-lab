param(
    [string]$BaseUrl = 'http://127.0.0.1:18080',
    [string]$IdentityUrl = 'http://127.0.0.1:18081'
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/lab-api.ps1"
$BaseUrl = $BaseUrl.TrimEnd('/')
$analyst = Wait-LabToken $IdentityUrl 'analyst'
$supervisor = Get-LabToken $IdentityUrl 'supervisor'
$question = 'Route duplicate charge billing tickets'

for ($attempt = 1; $attempt -le 25; $attempt++) {
    try {
        Invoke-RestMethod -Uri "$BaseUrl/sources/ticket-routing-v1-billing" -TimeoutSec 3 `
            -Headers @{ Authorization = "Bearer $analyst" } | Out-Null
        break
    } catch {
        if ($attempt -eq 25) { throw }
        Start-Sleep -Seconds 1
    }
}

function Show-Answer([string]$Revision, [string]$Queue) {
    $result = Invoke-LabQuestion $BaseUrl $supervisor $question
    $answer = $result.Body
    $chunkId = "supervisor-routing-$Revision-duplicate-charge"
    if ($result.HttpStatus -ne 200 -or $answer.status -ne 'answered' -or
        $answer.answer -notmatch [regex]::Escape($Queue) -or $answer.citations.chunkId -notcontains $chunkId) {
        $result | ConvertTo-Json -Depth 7 | Write-Host
        throw "The supervisor answer for $Revision did not identify the expected queue and citation."
    }
    $citation = $answer.citations | Where-Object chunkId -eq $chunkId
    $sourceUrl = $BaseUrl + $citation.sourceUrl
    $source = Invoke-RestMethod -Uri $sourceUrl -TimeoutSec 5 -Headers @{ Authorization = "Bearer $supervisor" }
    if ($source.chunkId -ne $chunkId -or $source.revisionId -ne $Revision) {
        throw 'The citation did not resolve to the expected revision.'
    }
    [pscustomobject]@{ Role = 'supervisor'; Response = $answer; SourceUrl = $sourceUrl; Source = $source } |
        ConvertTo-Json -Depth 7
    docker compose logs --no-color app | Select-String -SimpleMatch "requestId=$($answer.requestId) modelEvidenceChunkIds=" |
        ForEach-Object Line
}

Set-LabRevision $BaseUrl $supervisor 'v2'
try {
    $result = Invoke-LabQuestion $BaseUrl $analyst $question
    if ($result.HttpStatus -ne 200 -or $result.Body.status -ne 'insufficient_evidence') {
        throw 'The analyst did not abstain for the supervisor exception.'
    }
    [pscustomobject]@{ Role = 'analyst'; Response = $result.Body } | ConvertTo-Json -Depth 7
    $status = (Invoke-WebRequest -Uri "$BaseUrl/sources/supervisor-routing-v2-duplicate-charge" -TimeoutSec 5 `
        -Headers @{ Authorization = "Bearer $analyst" } -SkipHttpErrorCheck).StatusCode
    if ($status -ne 404) { throw 'The restricted source is reachable by the analyst.' }
    Show-Answer 'v2' 'Revenue Assurance'
    Set-LabRevision $BaseUrl $supervisor 'v3'
    Show-Answer 'v3' 'Payments Review'
    $status = (Invoke-WebRequest -Uri "$BaseUrl/sources/supervisor-routing-v2-duplicate-charge" -TimeoutSec 5 `
        -Headers @{ Authorization = "Bearer $supervisor" } -SkipHttpErrorCheck).StatusCode
    if ($status -ne 404) { throw 'The archived source is still reachable.' }
} finally {
    Set-LabRevision $BaseUrl $supervisor 'v2'
}
