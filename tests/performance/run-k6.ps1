[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$BaseUrl,
    [Parameter(Mandatory)][string]$EnvFile,
    [ValidateSet('Local', 'Server')][string]$Scope = 'Local',
    [string]$Server,
    [string]$SshUser,
    [string]$IdentityFile = (Join-Path $HOME '.ssh\id_rsa'),
    [Parameter(Mandatory)][string]$OutputPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

if ($Scope -eq 'Server' -and
    ([string]::IsNullOrWhiteSpace($Server) -or [string]::IsNullOrWhiteSpace($SshUser))) {
    throw 'Server and SshUser are required when Scope is Server.'
}

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
. (Join-Path $repositoryRoot 'tests\support\Acceptance.Http.ps1')
$environment = Read-AcceptanceEnvironment -Path $EnvFile
$teacherPassword = Get-RequiredEnvironmentValue -Environment $environment -Name 'EDUTWIN_DEMO_TEACHER_PASSWORD'
$teacherRecord = Get-DemoTeacherRecord -RepositoryRoot $repositoryRoot
$studentPassword = Get-RequiredEnvironmentValue -Environment $environment -Name 'EDUTWIN_DEMO_STUDENT_PASSWORD'
$studentsPath = Join-Path $repositoryRoot 'data\demo\generated\database\students.csv'
$questionsPath = Join-Path $repositoryRoot 'data\demo\generated\database\questions.csv'
$scriptPath = Join-Path $repositoryRoot 'tests\performance\k6.js'
foreach ($path in @($studentsPath, $questionsPath, $scriptPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required performance input is missing: $path"
    }
}
$composeFile = Join-Path $repositoryRoot 'infra\compose\compose.yaml'
$compose = @('compose', '--env-file', ([System.IO.Path]::GetFullPath($EnvFile)), '-f', $composeFile)
$IdentityFile = [System.IO.Path]::GetFullPath($IdentityFile)
if ($Scope -eq 'Server' -and -not (Test-Path -LiteralPath $IdentityFile -PathType Leaf)) {
    throw "Performance SSH identity file is missing: $IdentityFile"
}

function Convert-PagingCounters {
    param([Parameter(Mandatory)][string[]]$Lines)
    $values = @{}
    foreach ($line in $Lines) {
        if ($line -match '^(pswpin|pswpout)\s+([0-9]+)$') {
            $values[$matches[1]] = [long]$matches[2]
        }
    }
    if ($values.Count -ne 2) { throw 'Linux paging counters are incomplete.' }
    return [ordered]@{ pageIn = $values.pswpin; pageOut = $values.pswpout }
}

function Get-PagingCounters {
    if ($Scope -eq 'Local') {
        $lines = & docker @compose exec -T backend sh -c "grep -E '^(pswpin|pswpout) ' /proc/vmstat"
    }
    else {
        $lines = & ssh `
            -o BatchMode=yes `
            -o StrictHostKeyChecking=yes `
            -i $IdentityFile `
            "${SshUser}@${Server}" `
            "grep -E '^(pswpin|pswpout) ' /proc/vmstat"
    }
    if ($LASTEXITCODE -ne 0) { throw 'Linux paging counters could not be read.' }
    return Convert-PagingCounters -Lines @($lines)
}

function Copy-RemoteFileWithRetry {
    param(
        [Parameter(Mandatory)][string]$Source,
        [Parameter(Mandatory)][string]$Destination,
        [Parameter(Mandatory)][string[]]$SshOptions,
        [int]$MaximumAttempts = 5
    )

    for ($attempt = 1; $attempt -le $MaximumAttempts; $attempt++) {
        & scp -q @SshOptions $Source $Destination
        if ($LASTEXITCODE -eq 0) { return }
        Start-Sleep -Seconds 1
    }
    throw "Remote file transfer did not complete after $MaximumAttempts attempts: $Source"
}

$teacher = Connect-EduTwinUser `
    -BaseUrl $BaseUrl `
    -Username ([string]$teacherRecord.username) `
    -Password $teacherPassword
$courses = (Invoke-AcceptanceHttp `
        -BaseUrl $BaseUrl `
        -Path '/api/v1/courses' `
        -Token ([string]$teacher.accessToken)).Body
$studentById = @{}
foreach ($row in Import-Csv -LiteralPath $studentsPath) {
    $studentById[[string]$row.student_id] = $row
}
$answerAccounts = [System.Collections.Generic.List[object]]::new()
foreach ($course in $courses.items) {
    $courseId = [string]$course.courseId
    $dashboard = (Invoke-AcceptanceHttp `
            -BaseUrl $BaseUrl `
            -Path "/api/v1/teacher/courses/$courseId/dashboard" `
            -Token ([string]$teacher.accessToken)).Body
    if ($answerAccounts.Count -eq 5) { continue }
    foreach ($entry in @($dashboard.students | Sort-Object studentId)) {
        $studentId = [string]$entry.studentId
        if ($studentById.ContainsKey($studentId)) {
            $answerAccounts.Add([ordered]@{
                studentId = $studentId
                username = [string]$studentById[$studentId].username
                courseId = $courseId
            })
        }
        if ($answerAccounts.Count -eq 5) { break }
    }
}
Assert-AcceptanceCondition -Condition ($answerAccounts.Count -eq 5) -Message 'Performance verification requires five distinct student accounts.'
foreach ($account in $answerAccounts) {
    $session = Connect-EduTwinUser `
        -BaseUrl $BaseUrl `
        -Username ([string]$account.username) `
        -Password $studentPassword
    $account['accessToken'] = [string]$session.accessToken
}
$correctAnswers = [ordered]@{}
foreach ($row in Import-Csv -LiteralPath $questionsPath) {
    $correctAnswers[[string]$row.question_id] = [string]$row.correct_answer
}
Assert-AcceptanceCondition -Condition ($correctAnswers.Count -gt 0) -Message 'Performance verification has no correct-answer lookup.'

$runtime = Join-Path $repositoryRoot '.runtime\performance'
New-Item -Path $runtime -ItemType Directory -Force | Out-Null
$runId = [Guid]::NewGuid().ToString('N')
$inputPath = Join-Path $runtime "$runId-input.json"
$summaryPath = Join-Path $runtime "$runId-summary.json"
$containerBaseUrl = if ($Scope -eq 'Local' -and $BaseUrl -match '^http://(127\.0\.0\.1|localhost)(/|$)') {
    $BaseUrl -replace '^http://(127\.0\.0\.1|localhost)', 'http://caddy'
}
elseif ($Scope -eq 'Server') {
    'http://caddy'
}
else {
    $BaseUrl
}
$input = [ordered]@{
    baseUrl = $containerBaseUrl.TrimEnd('/')
    hostHeader = if ($containerBaseUrl -eq 'http://caddy') { 'localhost' } else { $null }
    teacherAccessToken = [string]$teacher.accessToken
    answerAccounts = @($answerAccounts)
    correctAnswers = $correctAnswers
}
Write-AcceptanceEvidence -Value $input -Path $inputPath

try {
    $performanceReportWritten = $false
    $remoteRuntime = $null
    $pagingBefore = Get-PagingCounters
    $configMount = ([System.IO.Path]::GetDirectoryName($inputPath)).Replace('\', '/')
    $scriptMount = ([System.IO.Path]::GetDirectoryName($scriptPath)).Replace('\', '/')
    $dockerArguments = @('run', '--rm')
    $projectName = if ($environment.Contains('COMPOSE_PROJECT_NAME')) {
        [string]$environment['COMPOSE_PROJECT_NAME']
    }
    else {
        'edutwin'
    }
    if ($projectName -notmatch '^[A-Za-z0-9][A-Za-z0-9_.-]*$') {
        throw "COMPOSE_PROJECT_NAME is not safe for Docker network selection: $projectName"
    }
    if ($Scope -eq 'Local') {
        $dockerArguments += @('--network', "$projectName-edge")
    }
    $dockerArguments += @(
        '-e', "EDUTWIN_K6_INPUT=/config/$runId-input.json",
        '-v', "${configMount}:/config",
        '-v', "${scriptMount}:/scripts:ro",
        'grafana/k6:0.56.0', 'run',
        '--summary-export', "/config/$runId-summary.json",
        '/scripts/k6.js'
    )
    if ($Scope -eq 'Local') {
        & docker @dockerArguments
        $k6ExitCode = $LASTEXITCODE
    }
    else {
        $remote = "${SshUser}@${Server}"
        $sshOptions = @(
            '-o', 'BatchMode=yes',
            '-o', 'StrictHostKeyChecking=yes',
            '-o', 'ConnectTimeout=15',
            '-o', 'ServerAliveInterval=15',
            '-o', 'ServerAliveCountMax=20',
            '-i', $IdentityFile
        )
        $remoteRuntime = "/tmp/edutwin-k6-$runId"
        & ssh @sshOptions $remote "install -d -m 0700 '$remoteRuntime'"
        if ($LASTEXITCODE -ne 0) { throw 'Remote k6 runtime directory could not be created.' }
        foreach ($upload in @(
                @{ Local = $inputPath; Remote = "$remoteRuntime/$runId-input.json" },
                @{ Local = $scriptPath; Remote = "$remoteRuntime/k6.js" }
            )) {
            Copy-RemoteFileWithRetry `
                -Source $upload.Local `
                -Destination "${remote}:$($upload.Remote)" `
                -SshOptions $sshOptions
        }
        $remoteDockerArguments = @(
            'docker', 'run', '--rm', '--user', '0:0', '--network', "$projectName-edge",
            '-e', "EDUTWIN_K6_INPUT=/config/$runId-input.json",
            '-v', "${remoteRuntime}:/config",
            'grafana/k6:0.56.0', 'run',
            '--summary-export', "/config/$runId-summary.json",
            '/config/k6.js'
        )
        $remoteCommand = $remoteDockerArguments -join ' '
        & ssh @sshOptions $remote "chmod 600 '$remoteRuntime/'* && $remoteCommand"
        $k6ExitCode = $LASTEXITCODE
        try {
            Copy-RemoteFileWithRetry `
                -Source "${remote}:$remoteRuntime/$runId-summary.json" `
                -Destination $summaryPath `
                -SshOptions $sshOptions
        }
        catch {
            if ($k6ExitCode -eq 0) { throw }
        }
    }
    if ($k6ExitCode -ne 0) {
        throw "k6 acceptance load failed with exit code $k6ExitCode."
    }
    if (-not (Test-Path -LiteralPath $summaryPath -PathType Leaf)) {
        throw 'k6 did not produce its machine-readable summary.'
    }
    $pagingAfter = Get-PagingCounters
    $pagingDelta = [ordered]@{
        pageIn = [long]$pagingAfter.pageIn - [long]$pagingBefore.pageIn
        pageOut = [long]$pagingAfter.pageOut - [long]$pagingBefore.pageOut
    }
    $maximumPageOutPages = 256
    Assert-AcceptanceCondition `
        -Condition ($pagingDelta.pageOut -le $maximumPageOutPages) `
        -Message "Sustained swap page-out occurred during load: in=$($pagingDelta.pageIn), out=$($pagingDelta.pageOut), max=$maximumPageOutPages."
    $summary = Get-Content -LiteralPath $summaryPath -Raw | ConvertFrom-Json -Depth 30
    function Get-P95 {
        param([Parameter(Mandatory)][string]$Metric)

        $metricProperty = $summary.metrics.PSObject.Properties[$Metric]
        if ($null -eq $metricProperty) {
            throw "k6 summary omits metric $Metric."
        }
        $metricValue = $metricProperty.Value
        $containers = @($metricValue)
        $valuesProperty = $metricValue.PSObject.Properties['values']
        if ($null -ne $valuesProperty) {
            $containers = @($valuesProperty.Value, $metricValue)
        }
        foreach ($container in $containers) {
            if ($null -eq $container) { continue }
            $percentileProperty = $container.PSObject.Properties['p(95)']
            if ($null -ne $percentileProperty -and $null -ne $percentileProperty.Value) {
                return [double]$percentileProperty.Value
            }
        }
        $available = @($metricValue.PSObject.Properties.Name) -join ', '
        throw "k6 summary omits p(95) for $Metric; available fields: $available."
    }

    $cachedMetric = 'http_req_duration'
    if ($null -ne $summary.metrics.PSObject.Properties['http_req_duration{kind:cached}']) {
        $cachedMetric = 'http_req_duration{kind:cached}'
    }
    $cachedP95 = Get-P95 -Metric $cachedMetric
    $answerP95 = Get-P95 -Metric 'answer_transaction_ms'
    $sseP95 = Get-P95 -Metric 'event_to_sse_completion_ms'
    Assert-AcceptanceCondition -Condition ($cachedP95 -lt 1000) -Message "Cached request P95 exceeded 1 second: $cachedP95 ms."
    Assert-AcceptanceCondition -Condition ($answerP95 -lt 500) -Message "Answer transaction P95 exceeded 500 ms: $answerP95 ms."
    Assert-AcceptanceCondition -Condition ($sseP95 -lt 5000) -Message "Event-to-SSE completion P95 exceeded 5 seconds: $sseP95 ms."

    $containerStability = @()
    if ($Scope -eq 'Local') {
        foreach ($service in @('mysql', 'redis', 'model-service', 'backend', 'frontend', 'caddy')) {
            $containerId = [string](& docker @compose ps -q $service | Select-Object -First 1)
            if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($containerId)) {
                throw "Performance stability check cannot find service: $service"
            }
            $state = @(& docker inspect $containerId | ConvertFrom-Json)[0]
            Assert-AcceptanceCondition `
                -Condition (-not [bool]$state.State.OOMKilled -and [int]$state.RestartCount -eq 0) `
                -Message "Service failed OOM/restart stability during load: $service"
            $containerStability += [ordered]@{
                service = $service
                oomKilled = [bool]$state.State.OOMKilled
                restartCount = [int]$state.RestartCount
            }
        }
    }
    $report = [ordered]@{
        schemaVersion = 1
        kind = 'edutwin-performance-verification'
        status = 'PASSED'
        scope = $Scope
        baseUrl = $BaseUrl
        verifiedAt = [DateTimeOffset]::UtcNow.ToString('o')
        workload = [ordered]@{
            browsingVirtualUsers = 50
            simultaneousAnswerUsers = 5
            durationSeconds = 30
        }
        metrics = [ordered]@{
            cachedRequestP95Ms = $cachedP95
            answerTransactionP95Ms = $answerP95
            eventToSseCompletionP95Ms = $sseP95
        }
        thresholds = [ordered]@{
            cachedRequestP95Ms = 1000
            answerTransactionP95Ms = 500
            eventToSseCompletionP95Ms = 5000
        }
        containerStability = $containerStability
        swapPaging = [ordered]@{
            before = $pagingBefore
            after = $pagingAfter
            delta = $pagingDelta
            maximumPageOutPages = $maximumPageOutPages
        }
    }
    Write-AcceptanceEvidence -Value $report -Path $OutputPath
    $performanceReportWritten = $true
    $report
}
finally {
    if ($null -ne $remoteRuntime) {
        & ssh `
            -o BatchMode=yes `
            -o StrictHostKeyChecking=yes `
            -i $IdentityFile `
            "${SshUser}@${Server}" `
            "rm -rf -- '$remoteRuntime'" 2>$null
    }
    Remove-Item -LiteralPath $inputPath -Force -ErrorAction SilentlyContinue
    if ($performanceReportWritten) {
        Remove-Item -LiteralPath $summaryPath -Force -ErrorAction SilentlyContinue
    }
    elseif (Test-Path -LiteralPath $summaryPath -PathType Leaf) {
        Write-Warning "Preserved failed k6 summary for diagnosis: $summaryPath"
    }
}
