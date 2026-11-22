param([string]$BaseUrl = 'http://127.0.0.1:18080')

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')

function Ask([string]$Question) {
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/questions" -ContentType 'application/json' `
        -Body (@{ question = $Question } | ConvertTo-Json -Compress) -TimeoutSec 40
}

for ($attempt = 1; $attempt -le 20; $attempt++) {
    try {
        Invoke-RestMethod -Uri "$BaseUrl/sources/ticket-routing-v1-billing" -TimeoutSec 2 | Out-Null
        break
    } catch {
        if ($attempt -eq 20) { throw }
        Start-Sleep -Seconds 1
    }
}

$answer = Ask 'Where do I route billing tickets?'
if ($answer.status -ne 'answered' -or $answer.answer -notmatch 'Billing Operations' -or
    $answer.citations.Count -ne 1 -or $answer.citations[0].chunkId -ne 'ticket-routing-v1-billing') {
    throw 'The answer did not cite the Billing tickets section and identify the expected queue.'
}
$answer | ConvertTo-Json -Depth 6

$source = Invoke-RestMethod -Uri ($BaseUrl + $answer.citations[0].sourceUrl) -TimeoutSec 5
if ($source.chunkId -ne $answer.citations[0].chunkId -or $source.revisionId -ne $answer.citations[0].revisionId) {
    throw 'The citation did not resolve to the expected source.'
}
$source | ConvertTo-Json -Depth 6

$abstention = Ask 'What is the travel reimbursement policy?'
if ($abstention.status -ne 'insufficient_evidence' -or $abstention.citations.Count -ne 0) {
    throw 'The application did not abstain when the corpus lacked the policy.'
}
$abstention | ConvertTo-Json -Depth 6
