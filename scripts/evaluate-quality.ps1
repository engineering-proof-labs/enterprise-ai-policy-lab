param(
    [string]$BaseUrl = 'http://127.0.0.1:18080',
    [string]$IdentityUrl = 'http://127.0.0.1:18081',
    [ValidateRange(1, 10)][int]$Repetitions = 2
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/lab-api.ps1"
$BaseUrl = $BaseUrl.TrimEnd('/')
$fixture = Get-Content "$PSScriptRoot/../src/main/resources/evaluation/questions.json" -Raw | ConvertFrom-Json
$cases = @($fixture.cases | Where-Object split -eq 'holdout')
$since = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
$ollamaVersion = docker compose exec -T ollama ollama --version | Out-String
if ($LASTEXITCODE -ne 0) { throw 'Could not read the Ollama version.' }
$model = docker compose exec -T ollama ollama list | Select-String '^qwen2\.5:1\.5b\s+(?<id>[a-f0-9]+)'
if ($LASTEXITCODE -ne 0 -or !$model) { throw 'Evaluation requires the qwen2.5:1.5b fixture model.' }
$tokens = @{
    analyst = Wait-LabToken $IdentityUrl 'analyst'
    supervisor = Get-LabToken $IdentityUrl 'supervisor'
}

$results = @(
    try {
        for ($run = 1; $run -le $Repetitions; $run++) {
            foreach ($case in $cases) {
                Set-LabRevision $BaseUrl $tokens.supervisor $case.revision
                $result = Invoke-LabQuestion $BaseUrl $tokens[$case.group] $case.question
                $citations = @($result.Body.citations | Where-Object { $_ })
                $sources = @(
                    foreach ($citation in $citations) {
                        Invoke-RestMethod -Uri ($BaseUrl + $citation.sourceUrl) -TimeoutSec 5 `
                            -Headers @{ Authorization = "Bearer $($tokens[$case.group])" }
                    }
                )
                [pscustomobject]@{
                    id = $case.id
                    run = $run
                    profile = $case.group
                    revision = $case.revision
                    required = @($case.expected)
                    http = $result.HttpStatus
                    requestId = $result.RequestId
                    status = $result.Body.status
                    code = $result.Body.code
                    answer = $result.Body.answer
                    sources = $sources
                }
            }
        }
    } finally {
        Set-LabRevision $BaseUrl $tokens.supervisor 'v2'
    }
)

$logs = @(docker compose logs --no-color --since $since app)
if ($LASTEXITCODE -ne 0) { throw 'Could not read app telemetry.' }
foreach ($row in $results) {
    $lines = @($logs | Select-String -SimpleMatch "requestId=$($row.requestId) " | ForEach-Object Line)
    $retrieval = $lines | Select-String 'retrievalMs=(?<ms>\d+) selectedEvidenceChunkIds=\[(?<ids>[^\]]*)\]' |
        Select-Object -First 1
    $generation = $lines | Select-String 'generationMs=(?<ms>\d+) generationStatus=(?<state>\S+)' |
        Select-Object -First 1
    $usage = $lines | Select-String 'promptTokens=(?<in>\d+) completionTokens=(?<out>\d+) totalTokens=(?<total>\d+)' |
        Select-Object -First 1
    $selected = @()
    if ($retrieval) {
        $selected = @($retrieval.Matches[0].Groups['ids'].Value -split ', ' | Where-Object {$_})
    }
    if ($row.http -eq 200 -and !$retrieval) { throw "Missing retrieval telemetry for $($row.id)." }

    $missing = @($row.required | Where-Object {$_ -notin $selected})
    $classification = if ($row.http -ge 500 -and $row.code -eq 'invalid_model_output') {
        'generation_invalid'
    } elseif ($row.http -ne 200) {
        'operational_or_http_failure'
    } elseif ($missing.Count -gt 0) {
        'retrieval_miss'
    } elseif ($row.status -eq 'insufficient_evidence' -and $row.required.Count -gt 0) {
        'generation_incorrect_abstention'
    } elseif ($row.status -eq 'insufficient_evidence') {
        'correct_abstention'
    } else {
        'answer_requires_source_review'
    }
    $row | Add-Member NoteProperty selected $selected
    $row | Add-Member NoteProperty retrievalMs $(if ($retrieval) {[int]$retrieval.Matches[0].Groups['ms'].Value})
    $row | Add-Member NoteProperty generationMs $(if ($generation) {[int]$generation.Matches[0].Groups['ms'].Value})
    $row | Add-Member NoteProperty promptTokens $(if ($usage) {[int]$usage.Matches[0].Groups['in'].Value})
    $row | Add-Member NoteProperty completionTokens $(if ($usage) {[int]$usage.Matches[0].Groups['out'].Value})
    $row | Add-Member NoteProperty totalTokens $(if ($usage) {[int]$usage.Matches[0].Groups['total'].Value})
    $row | Add-Member NoteProperty classification $classification
    "QUALITY_CASE " + ($row | ConvertTo-Json -Depth 8 -Compress)
}

"QUALITY_RUN " + ([pscustomobject]@{
    corpus = $fixture.corpus
    split = 'holdout'
    strategy = 'postgresql_text'
    model = 'qwen2.5:1.5b'
    modelId = $model.Matches[0].Groups['id'].Value
    ollama = $ollamaVersion.Trim()
    repetitions = $Repetitions
    questions = $cases.Count
    requests = $results.Count
    temperature = 0
    seed = 42
    contextTokens = 4096
    maxOutputTokens = 256
} | ConvertTo-Json -Compress)
