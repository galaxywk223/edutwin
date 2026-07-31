Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Read-AcceptanceEnvironment {
    param([Parameter(Mandatory)][string]$Path)

    $resolved = [System.IO.Path]::GetFullPath($Path)
    if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) {
        throw "Acceptance environment file is missing: $resolved"
    }
    $values = [ordered]@{}
    foreach ($line in Get-Content -LiteralPath $resolved) {
        if ($line -match '^\s*#' -or [string]::IsNullOrWhiteSpace($line)) {
            continue
        }
        if ($line -notmatch '^([^=]+)=(.*)$') {
            throw "Invalid environment assignment in ${resolved}: $line"
        }
        $values[$matches[1].Trim()] = $matches[2]
    }
    return $values
}

function Get-RequiredEnvironmentValue {
    param(
        [Parameter(Mandatory)][System.Collections.IDictionary]$Environment,
        [Parameter(Mandatory)][string]$Name
    )

    $value = if ($Environment.Contains($Name)) { [string]$Environment[$Name] } else { '' }
    if ([string]::IsNullOrWhiteSpace($value) -or $value -match '^CHANGE_ME') {
        throw "Required acceptance environment value is missing or unchanged: $Name"
    }
    return $value
}

function Join-AcceptanceUri {
    param(
        [Parameter(Mandatory)][string]$BaseUrl,
        [Parameter(Mandatory)][string]$Path
    )

    return $BaseUrl.TrimEnd('/') + '/' + $Path.TrimStart('/')
}

function Invoke-AcceptanceHttp {
    param(
        [Parameter(Mandatory)][string]$BaseUrl,
        [Parameter(Mandatory)][string]$Path,
        [ValidateSet('GET', 'POST', 'PUT', 'DELETE', 'PATCH')][string]$Method = 'GET',
        [string]$Token,
        [object]$Body,
        [hashtable]$Headers = @{},
        [int[]]$ExpectedStatus = @(200),
        [int]$TimeoutSeconds = 30
    )

    $handler = [System.Net.Http.HttpClientHandler]::new()
    $handler.UseProxy = $false
    $client = [System.Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSeconds)
    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::new($Method),
        (Join-AcceptanceUri -BaseUrl $BaseUrl -Path $Path)
    )
    try {
        $request.Headers.Accept.ParseAdd('application/json')
        if (-not [string]::IsNullOrWhiteSpace($Token)) {
            $request.Headers.Authorization =
                [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $Token)
        }
        foreach ($entry in $Headers.GetEnumerator()) {
            if (-not $request.Headers.TryAddWithoutValidation([string]$entry.Key, [string]$entry.Value)) {
                throw "HTTP request header could not be added: $($entry.Key)"
            }
        }
        if ($PSBoundParameters.ContainsKey('Body')) {
            $json = $Body | ConvertTo-Json -Depth 30 -Compress
            $request.Content = [System.Net.Http.StringContent]::new(
                $json,
                [System.Text.Encoding]::UTF8,
                'application/json'
            )
        }

        $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $raw = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $stopwatch.Stop()
        $status = [int]$response.StatusCode
        $responseHeaders = [ordered]@{}
        foreach ($header in $response.Headers) {
            $responseHeaders[$header.Key] = @($header.Value)
        }
        foreach ($header in $response.Content.Headers) {
            $responseHeaders[$header.Key] = @($header.Value)
        }
        $parsed = $null
        if (-not [string]::IsNullOrWhiteSpace($raw) -and
            [string]$response.Content.Headers.ContentType.MediaType -match 'json') {
            try {
                $parsed = $raw | ConvertFrom-Json -Depth 40
            }
            catch {
                throw "HTTP response is not valid JSON: $Method $Path status=$status"
            }
        }
        if ($status -notin $ExpectedStatus) {
            $detail = if ($null -ne $parsed -and $null -ne $parsed.detail) {
                [string]$parsed.detail
            }
            else {
                $raw
            }
            throw "Unexpected HTTP status for $Method ${Path}: $status; $detail"
        }
        return [pscustomobject]@{
            StatusCode = $status
            Headers = $responseHeaders
            Body = $parsed
            RawBody = $raw
            ElapsedMs = [int64]$stopwatch.ElapsedMilliseconds
        }
    }
    finally {
        $request.Dispose()
        $client.Dispose()
        $handler.Dispose()
    }
}

function Connect-EduTwinUser {
    param(
        [Parameter(Mandatory)][string]$BaseUrl,
        [Parameter(Mandatory)][string]$Username,
        [Parameter(Mandatory)][string]$Password
    )

    $response = Invoke-AcceptanceHttp `
        -BaseUrl $BaseUrl `
        -Path '/api/v1/auth/login' `
        -Method POST `
        -Body ([ordered]@{ username = $Username; password = $Password })
    if ([string]::IsNullOrWhiteSpace([string]$response.Body.accessToken) -or
        [string]$response.Body.tokenType -ne 'Bearer') {
        throw "Authentication response is missing a Bearer token for $Username."
    }
    return $response.Body
}

function Assert-AcceptanceCondition {
    param(
        [Parameter(Mandatory)][bool]$Condition,
        [Parameter(Mandatory)][string]$Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

function Assert-AcceptanceProperties {
    param(
        [Parameter(Mandatory)][object]$Value,
        [Parameter(Mandatory)][string[]]$Names,
        [Parameter(Mandatory)][string]$Context
    )

    if ($null -eq $Value) {
        throw "$Context is null."
    }
    $present = @($Value.PSObject.Properties.Name)
    $missing = @($Names | Where-Object { $_ -notin $present })
    if ($missing.Count -gt 0) {
        throw "$Context is missing properties: $($missing -join ', ')"
    }
}

function Write-AcceptanceEvidence {
    param(
        [Parameter(Mandatory)][object]$Value,
        [Parameter(Mandatory)][string]$Path
    )

    $resolved = [System.IO.Path]::GetFullPath($Path)
    New-Item -Path (Split-Path -Parent $resolved) -ItemType Directory -Force | Out-Null
    $temporary = "$resolved.tmp"
    try {
        [System.IO.File]::WriteAllText(
            $temporary,
            (($Value | ConvertTo-Json -Depth 40) + "`n"),
            [System.Text.UTF8Encoding]::new($false)
        )
        Move-Item -LiteralPath $temporary -Destination $resolved -Force
    }
    finally {
        if (Test-Path -LiteralPath $temporary) {
            Remove-Item -LiteralPath $temporary -Force
        }
    }
}

function Get-DemoStudentRecord {
    param(
        [Parameter(Mandatory)][string]$RepositoryRoot,
        [Parameter(Mandatory)][string]$StudentId
    )

    $path = Join-Path $RepositoryRoot 'data\demo\generated\database\students.csv'
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Generated student table is missing: $path"
    }
    $matches = @(Import-Csv -LiteralPath $path | Where-Object student_id -eq $StudentId)
    if ($matches.Count -ne 1 -or [string]::IsNullOrWhiteSpace([string]$matches[0].username)) {
        throw "Generated student table does not contain exactly one record for $StudentId."
    }
    return $matches[0]
}

function Get-DemoTeacherRecord {
    param([Parameter(Mandatory)][string]$RepositoryRoot)

    $path = Join-Path $RepositoryRoot 'data\demo\generated\database\teachers.csv'
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Generated teacher table is missing: $path"
    }
    $records = @(Import-Csv -LiteralPath $path)
    if ($records.Count -eq 0) {
        throw 'Generated teacher table is empty.'
    }
    $record = $records[0]
    foreach ($name in @('teacher_id', 'username', 'staff_number', 'staff_key')) {
        if ([string]::IsNullOrWhiteSpace([string]$record.$name)) {
            throw "Generated teacher record is missing $name."
        }
    }
    if (@($records | Where-Object username -eq $record.username).Count -ne 1) {
        throw "Generated teacher username is not unique: $($record.username)"
    }
    return $record
}

function Get-AcceptanceAnalysisSql {
    param([Parameter(Mandatory)][ValidatePattern('^[0-9a-fA-F-]{36}$')][string]$JobId)

    return @"
SELECT aj.id, aj.status, aj.answer_event_id, aj.target_snapshot_id,
       aj.effective_model_versions, COALESCE(aj.degraded_stages, JSON_ARRAY()),
       COALESCE(d.id, ''), COALESCE(d.provider, ''),
       COALESCE(d.effective_model, ''), COALESCE(d.degraded, 0),
       COALESCE(JSON_UNQUOTE(JSON_EXTRACT(d.structured_content, '$.toolCallVerified')), 'false'),
       (SELECT COUNT(*) FROM twin_snapshot ts
        WHERE ts.id = aj.target_snapshot_id
          AND ts.analysis_job_id = aj.id
          AND ts.answer_event_id = aj.answer_event_id),
       (SELECT COUNT(*) FROM learning_plan lp
        WHERE lp.source_snapshot_id = aj.target_snapshot_id
          AND lp.source_job_id = aj.id),
       (SELECT COUNT(DISTINCT de.risk_model_version)
        FROM diagnosis_evidence de WHERE de.diagnosis_id = d.id),
       COALESCE((SELECT MIN(de.risk_model_version)
                 FROM diagnosis_evidence de WHERE de.diagnosis_id = d.id), '')
FROM analysis_job aj
LEFT JOIN diagnosis d ON d.analysis_job_id = aj.id
WHERE aj.id = '$JobId';
"@
}

function ConvertFrom-AcceptanceAnalysisRow {
    param([Parameter(Mandatory)][string]$Row)

    $fields = $Row.Split("`t")
    if ($fields.Count -ne 15) {
        throw "Unexpected acceptance analysis row with $($fields.Count) fields."
    }
    $models = @($fields[4] | ConvertFrom-Json -Depth 20)
    $degradationReasons = @($fields[5] | ConvertFrom-Json -Depth 10)
    return [pscustomobject]@{
        jobId = $fields[0]
        status = $fields[1]
        answerEventId = $fields[2]
        snapshotId = $fields[3]
        effectiveModelVersions = $models
        degradationReasons = $degradationReasons
        diagnosisId = $fields[6]
        diagnosisProvider = $fields[7]
        diagnosisModel = $fields[8]
        diagnosisDegraded = $fields[9] -eq '1'
        toolCallVerified = $fields[10] -eq 'true'
        snapshotLinkCount = [int]$fields[11]
        planLinkCount = [int]$fields[12]
        evidenceRiskModelCount = [int]$fields[13]
        evidenceRiskModelVersion = $fields[14]
    }
}

function Invoke-LocalAcceptanceMySql {
    param(
        [Parameter(Mandatory)][string[]]$ComposeArguments,
        [Parameter(Mandatory)][string]$Sql
    )

    $command = 'mysql --batch --skip-column-names -u"$MYSQL_USER" --password="$MYSQL_PASSWORD" "$MYSQL_DATABASE"'
    $output = $Sql | & docker @ComposeArguments exec -T mysql sh -c $command 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw 'Acceptance MySQL query failed.'
    }
    return @($output)
}

function Set-AcceptanceComposeImageTag {
    param([Parameter(Mandatory)][string[]]$ComposeArguments)

    $tags = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal)
    foreach ($service in @('backend', 'model-service', 'frontend')) {
        $containerId = [string](& docker @ComposeArguments ps -q $service | Select-Object -First 1)
        if ([string]::IsNullOrWhiteSpace($containerId)) {
            throw "Acceptance image pinning cannot find running service: $service"
        }
        $image = [string](& docker inspect $containerId --format '{{.Config.Image}}')
        if ($image -notmatch ':([^/:]+)$') {
            throw "Acceptance image pinning cannot parse image for $service."
        }
        [void]$tags.Add($matches[1])
    }
    if ($tags.Count -ne 1) {
        throw "Acceptance application services do not share one image tag: $($tags -join ', ')"
    }
    $env:EDUTWIN_IMAGE_TAG = [string]@($tags)[0]
    return $env:EDUTWIN_IMAGE_TAG
}

function Get-LocalAcceptanceAnalysisRecord {
    param(
        [Parameter(Mandatory)][string[]]$ComposeArguments,
        [Parameter(Mandatory)][string]$JobId
    )

    $rows = @(Invoke-LocalAcceptanceMySql `
            -ComposeArguments $ComposeArguments `
            -Sql (Get-AcceptanceAnalysisSql -JobId $JobId))
    if ($rows.Count -ne 1) {
        throw "Acceptance analysis query returned $($rows.Count) rows for $JobId."
    }
    return ConvertFrom-AcceptanceAnalysisRow -Row ([string]$rows[0])
}

function Get-LocalAcceptanceCurrentSnapshotId {
    param(
        [Parameter(Mandatory)][string[]]$ComposeArguments,
        [Parameter(Mandatory)][ValidatePattern('^[0-9a-fA-F-]{36}$')][string]$CourseId,
        [Parameter(Mandatory)][ValidatePattern('^[0-9a-fA-F-]{36}$')][string]$StudentId
    )

    $sql = "SELECT snapshot_id FROM twin_current_pointer WHERE course_id = '$CourseId' AND student_id = '$StudentId';"
    $rows = @(Invoke-LocalAcceptanceMySql -ComposeArguments $ComposeArguments -Sql $sql)
    if ($rows.Count -ne 1 -or [string]$rows[0] -notmatch '^[0-9a-fA-F-]{36}$') {
        throw 'Acceptance current snapshot query did not return exactly one identifier.'
    }
    return [string]$rows[0]
}

function Get-DemoCorrectAnswer {
    param(
        [Parameter(Mandatory)][string]$RepositoryRoot,
        [Parameter(Mandatory)][string]$QuestionId
    )

    $path = Join-Path $RepositoryRoot 'data\demo\generated\database\questions.csv'
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Generated question table is missing: $path"
    }
    $matches = @(Import-Csv -LiteralPath $path | Where-Object question_id -eq $QuestionId)
    if ($matches.Count -ne 1 -or [string]::IsNullOrWhiteSpace([string]$matches[0].correct_answer)) {
        throw "Generated question table does not contain one answer for $QuestionId."
    }
    return [string]$matches[0].correct_answer
}

function Wait-EduTwinJob {
    param(
        [Parameter(Mandatory)][string]$BaseUrl,
        [Parameter(Mandatory)][string]$Token,
        [Parameter(Mandatory)][string]$JobId,
        [int]$TimeoutSeconds = 30
    )

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $response = Invoke-AcceptanceHttp `
            -BaseUrl $BaseUrl `
            -Path "/api/v1/analysis/jobs/$JobId" `
            -Token $Token
        if ([string]$response.Body.status -in @('COMPLETED', 'FAILED')) {
            return $response.Body
        }
        Start-Sleep -Milliseconds 100
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw "Analysis job did not reach a terminal state within $TimeoutSeconds seconds: $JobId"
}

function Wait-EduTwinSseTerminal {
    param(
        [Parameter(Mandatory)][string]$BaseUrl,
        [Parameter(Mandatory)][string]$Token,
        [Parameter(Mandatory)][string]$JobId,
        [long]$LastEventId = 0,
        [int]$TimeoutSeconds = 30
    )

    $handler = [System.Net.Http.HttpClientHandler]::new()
    $handler.UseProxy = $false
    $client = [System.Net.Http.HttpClient]::new($handler)
    $client.Timeout = [System.Threading.Timeout]::InfiniteTimeSpan
    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::Get,
        (Join-AcceptanceUri -BaseUrl $BaseUrl -Path "/api/v1/analysis/jobs/$JobId/events")
    )
    $request.Headers.Accept.ParseAdd('text/event-stream')
    $request.Headers.Authorization =
        [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $Token)
    if ($LastEventId -gt 0) {
        $request.Headers.TryAddWithoutValidation('Last-Event-ID', [string]$LastEventId) | Out-Null
    }
    $cancellation = [System.Threading.CancellationTokenSource]::new(
        [TimeSpan]::FromSeconds($TimeoutSeconds)
    )
    $events = [System.Collections.Generic.List[object]]::new()
    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $response = $client.SendAsync(
            $request,
            [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead,
            $cancellation.Token
        ).GetAwaiter().GetResult()
        if ([int]$response.StatusCode -ne 200 -or
            [string]$response.Content.Headers.ContentType.MediaType -ne 'text/event-stream') {
            $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            throw "SSE request failed for job ${JobId}: $([int]$response.StatusCode); $body"
        }
        $stream = $response.Content.ReadAsStream($cancellation.Token)
        $reader = [System.IO.StreamReader]::new($stream)
        try {
            $eventName = $null
            $eventId = $null
            $dataLines = [System.Collections.Generic.List[string]]::new()
            while (-not $cancellation.IsCancellationRequested) {
                $line = $reader.ReadLineAsync($cancellation.Token).AsTask().GetAwaiter().GetResult()
                if ($null -eq $line) {
                    break
                }
                if ($line.Length -eq 0) {
                    if ($dataLines.Count -gt 0) {
                        $data = ($dataLines -join "`n") | ConvertFrom-Json -Depth 40
                        Assert-AcceptanceProperties `
                            -Value $data `
                            -Names @('sequence', 'eventType', 'occurredAt', 'job') `
                            -Context 'SSE event data'
                        if ([string]$data.eventType -ne [string]$eventName -or
                            [long]$data.sequence -ne [long]$eventId) {
                            throw "SSE event metadata and data differ for job $JobId."
                        }
                        $events.Add($data)
                        if ([string]$data.eventType -in @('job.completed', 'job.failed')) {
                            $stopwatch.Stop()
                            return [pscustomobject]@{
                                Events = @($events)
                                TerminalJob = $data.job
                                ElapsedMs = [int64]$stopwatch.ElapsedMilliseconds
                            }
                        }
                    }
                    $eventName = $null
                    $eventId = $null
                    $dataLines.Clear()
                    continue
                }
                if ($line.StartsWith(':')) { continue }
                if ($line.StartsWith('id:')) { $eventId = $line.Substring(3).Trim(); continue }
                if ($line.StartsWith('event:')) { $eventName = $line.Substring(6).Trim(); continue }
                if ($line.StartsWith('data:')) { $dataLines.Add($line.Substring(5).TrimStart()) }
            }
        }
        finally {
            $reader.Dispose()
            $stream.Dispose()
            $response.Dispose()
        }
    }
    catch [System.OperationCanceledException] {
        throw "SSE did not reach a terminal event within $TimeoutSeconds seconds: $JobId"
    }
    finally {
        $stopwatch.Stop()
        $cancellation.Dispose()
        $request.Dispose()
        $client.Dispose()
        $handler.Dispose()
    }
    throw "SSE ended without a terminal event: $JobId"
}
