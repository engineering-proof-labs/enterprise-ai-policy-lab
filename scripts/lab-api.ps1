function Get-LabToken([string]$IdentityUrl, [string]$Role) {
    $token = Invoke-RestMethod -Method Post -TimeoutSec 5 -Uri (
        $IdentityUrl.TrimEnd('/') + '/realms/policy-lab/protocol/openid-connect/token'
    ) -ContentType 'application/x-www-form-urlencoded' -Body @{
        grant_type = 'password'
        client_id = 'policy-demo'
        username = $Role
        password = "$Role-demo"
    }
    $token.access_token
}

function Invoke-LabQuestion([string]$BaseUrl, [string]$Token, [string]$Question) {
    $BaseUrl = $BaseUrl.TrimEnd('/')
    $response = Invoke-WebRequest -Method Post -Uri "$BaseUrl/questions" -ContentType 'application/json' `
        -Headers @{ Authorization = "Bearer $Token" } -TimeoutSec 65 -SkipHttpErrorCheck `
        -Body (@{ question = $Question } | ConvertTo-Json -Compress)
    $content = $response.Content
    if ($content -is [byte[]]) { $content = [Text.Encoding]::UTF8.GetString($content) }
    [pscustomobject]@{
        HttpStatus = [int]$response.StatusCode
        RequestId = $response.Headers['X-Request-Id'] | Select-Object -First 1
        Body = if ($content) { $content | ConvertFrom-Json }
    }
}

function Set-LabRevision([string]$BaseUrl, [string]$Token, [string]$Revision) {
    Invoke-RestMethod -Method Put -Uri "$BaseUrl/policies/supervisor-routing/active-revision" `
        -ContentType 'application/json' -Headers @{ Authorization = "Bearer $Token" } -TimeoutSec 10 `
        -Body (@{ revisionId = $Revision } | ConvertTo-Json -Compress) | Out-Null
}

function Wait-LabToken([string]$IdentityUrl, [string]$Role) {
    $wait = [Diagnostics.Stopwatch]::StartNew()
    while ($true) {
        try { return Get-LabToken $IdentityUrl $Role } catch {
            if ($wait.Elapsed.TotalSeconds -ge 90) { throw }
            Start-Sleep -Seconds 1
        }
    }
}
