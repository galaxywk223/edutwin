[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$BaseUrl,
    [Parameter(Mandatory)][string]$EnvFile,
    [Parameter(Mandatory)][string]$OutputPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$composeFile = Join-Path $repositoryRoot 'infra\compose\compose.yaml'
. (Join-Path $repositoryRoot 'tests\support\Acceptance.Http.ps1')
$environment = Read-AcceptanceEnvironment -Path $EnvFile
$teacherPassword = Get-RequiredEnvironmentValue -Environment $environment -Name 'EDUTWIN_DEMO_TEACHER_PASSWORD'
$teacherRecord = Get-DemoTeacherRecord -RepositoryRoot $repositoryRoot
$studentPassword = Get-RequiredEnvironmentValue -Environment $environment -Name 'EDUTWIN_DEMO_STUDENT_PASSWORD'
$compose = @('compose', '--env-file', ([System.IO.Path]::GetFullPath($EnvFile)), '-f', $composeFile)
Set-AcceptanceComposeImageTag -ComposeArguments $compose | Out-Null

function Invoke-ComposeChecked {
    param([Parameter(Mandatory)][string[]]$Arguments)
    $output = & docker @compose @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose command failed: $($Arguments -join ' '); $($output -join ' ')"
    }
    return @($output)
}

function Wait-ComposeHealthy {
    param([Parameter(Mandatory)][string]$Service, [int]$TimeoutSeconds = 120)
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $containerId = [string](& docker @compose ps -q $Service | Select-Object -First 1)
        if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($containerId)) {
            $container = @(& docker inspect $containerId | ConvertFrom-Json)[0]
            $state = if ($null -ne $container.State.Health) {
                [string]$container.State.Health.Status
            }
            else {
                [string]$container.State.Status
            }
            if ($state -in @('healthy', 'running')) { return }
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw "Compose service did not become healthy: $Service"
}

function Wait-OutboxAttempt {
    param([Parameter(Mandatory)][string]$JobId, [int]$TimeoutSeconds = 10)
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    $arguments = @(
        'exec', '-T', 'mysql', 'mysql', '--batch', '--skip-column-names',
        "--user=$($environment['MYSQL_USER'])",
        "--password=$($environment['MYSQL_PASSWORD'])",
        "--database=$($environment['MYSQL_DATABASE'])"
    )
    $sql = "SELECT COALESCE(MAX(attempts), 0) FROM outbox_event WHERE aggregate_id = '$JobId'"
    do {
        $output = & docker @compose @arguments "--execute=$sql" 2>$null
        if ($LASTEXITCODE -eq 0 -and [int](@($output)[-1]) -ge 2) { return }
        Start-Sleep -Milliseconds 100
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw "Outbox did not record a failed Redis delivery attempt for job $JobId."
}

$teacher = Connect-EduTwinUser `
    -BaseUrl $BaseUrl `
    -Username ([string]$teacherRecord.username) `
    -Password $teacherPassword
$teacherToken = [string]$teacher.accessToken
$courses = (Invoke-AcceptanceHttp -BaseUrl $BaseUrl -Path '/api/v1/courses' -Token $teacherToken).Body
$courseId = [string]$courses.items[0].courseId
$dashboard = (Invoke-AcceptanceHttp `
        -BaseUrl $BaseUrl `
        -Path "/api/v1/teacher/courses/$courseId/dashboard" `
        -Token $teacherToken).Body
$selected = @($dashboard.students | Sort-Object `
        @{ Expression = 'riskProbability'; Descending = $true }, `
        @{ Expression = 'studentId'; Descending = $false })[0]
$studentId = [string]$selected.studentId
$studentRecord = Get-DemoStudentRecord -RepositoryRoot $repositoryRoot -StudentId $studentId
$student = Connect-EduTwinUser `
    -BaseUrl $BaseUrl `
    -Username ([string]$studentRecord.username) `
    -Password $studentPassword
$studentToken = [string]$student.accessToken
$historyPath = "/api/v1/courses/$courseId/students/$studentId/twin/history?limit=100"
$historyBefore = (Invoke-AcceptanceHttp -BaseUrl $BaseUrl -Path $historyPath -Token $studentToken).Body
$question = (Invoke-AcceptanceHttp `
        -BaseUrl $BaseUrl `
        -Path "/api/v1/courses/$courseId/practice/next" `
        -Token $studentToken).Body
$correct = Get-DemoCorrectAnswer -RepositoryRoot $repositoryRoot -QuestionId ([string]$question.questionId)
$payload = [ordered]@{
    questionId = [string]$question.questionId
    selectedChoiceId = $correct
    occurredAt = [DateTimeOffset]::UtcNow.AddMilliseconds(-100).ToString('o')
}
$key = "acceptance-restart-$([Guid]::NewGuid().ToString('N'))"
$redisStopped = $false
$backendStopped = $false
try {
    Invoke-ComposeChecked -Arguments @('stop', '--timeout', '10', 'redis') | Out-Null
    $redisStopped = $true
    $submitted = Invoke-AcceptanceHttp `
        -BaseUrl $BaseUrl `
        -Path "/api/v1/courses/$courseId/answers" `
        -Method POST `
        -Token $studentToken `
        -Headers @{ 'Idempotency-Key' = $key } `
        -Body $payload `
    -ExpectedStatus @(202)
    $jobId = [string]$submitted.Body.jobId
    $submittedAnalysis = Get-LocalAcceptanceAnalysisRecord -ComposeArguments $compose -JobId $jobId
    $targetSnapshotId = [string]$submittedAnalysis.snapshotId
    Wait-OutboxAttempt -JobId $jobId
    Invoke-ComposeChecked -Arguments @('stop', '--timeout', '20', 'backend') | Out-Null
    $backendStopped = $true
    Invoke-ComposeChecked -Arguments @('start', 'backend') | Out-Null
    $backendStopped = $false
    Wait-ComposeHealthy -Service 'backend'
    Invoke-ComposeChecked -Arguments @('start', 'redis') | Out-Null
    $redisStopped = $false
    Wait-ComposeHealthy -Service 'redis'
    $terminal = Wait-EduTwinSseTerminal `
        -BaseUrl $BaseUrl `
        -Token $studentToken `
        -JobId $jobId `
        -TimeoutSeconds 20
    Assert-AcceptanceCondition -Condition ([string]$terminal.TerminalJob.status -eq 'COMPLETED') -Message 'Queued task did not recover after backend and Redis restart.'
    $recoveredAnalysis = Get-LocalAcceptanceAnalysisRecord -ComposeArguments $compose -JobId $jobId
    Assert-AcceptanceCondition `
        -Condition ('REDIS_RETRY' -in @($recoveredAnalysis.degradationReasons)) `
        -Message 'Restart-recovered task did not retain REDIS_RETRY.'
}
finally {
    if ($redisStopped) { Invoke-ComposeChecked -Arguments @('start', 'redis') | Out-Null }
    if ($backendStopped) { Invoke-ComposeChecked -Arguments @('start', 'backend') | Out-Null }
    Wait-ComposeHealthy -Service 'redis'
    Wait-ComposeHealthy -Service 'backend'
}

$historyAfterRecovery = (Invoke-AcceptanceHttp -BaseUrl $BaseUrl -Path $historyPath -Token $studentToken).Body
$twinAfterRecovery = (Invoke-AcceptanceHttp `
        -BaseUrl $BaseUrl `
        -Path "/api/v1/courses/$courseId/students/$studentId/twin/current" `
        -Token $studentToken).Body
Assert-AcceptanceCondition `
    -Condition ([long]$historyAfterRecovery.total -eq [long]$historyBefore.total + 1) `
    -Message 'Restart recovery created zero or duplicate twin snapshots.'
$currentSnapshotId = Get-LocalAcceptanceCurrentSnapshotId `
    -ComposeArguments $compose `
    -CourseId $courseId `
    -StudentId $studentId
Assert-AcceptanceCondition -Condition ($currentSnapshotId -eq $targetSnapshotId) -Message 'Recovered task target snapshot is not current.'
$snapshotVersion = [long]$twinAfterRecovery.snapshotVersion

Invoke-ComposeChecked -Arguments @('stop', '--timeout', '60') | Out-Null
Invoke-ComposeChecked -Arguments @('up', '-d', '--no-build', '--wait', '--wait-timeout', '600', '--remove-orphans') | Out-Null
foreach ($service in @('mysql', 'redis', 'model-service', 'backend', 'frontend', 'caddy')) {
    Wait-ComposeHealthy -Service $service
}

$studentAfterRestart = Connect-EduTwinUser `
    -BaseUrl $BaseUrl `
    -Username ([string]$studentRecord.username) `
    -Password $studentPassword
$studentTokenAfterRestart = [string]$studentAfterRestart.accessToken
$jobAfterRestart = (Invoke-AcceptanceHttp `
        -BaseUrl $BaseUrl `
        -Path "/api/v1/analysis/jobs/$jobId" `
        -Token $studentTokenAfterRestart).Body
$twinAfterRestart = (Invoke-AcceptanceHttp `
        -BaseUrl $BaseUrl `
        -Path "/api/v1/courses/$courseId/students/$studentId/twin/current" `
        -Token $studentTokenAfterRestart).Body
$historyAfterRestart = (Invoke-AcceptanceHttp `
        -BaseUrl $BaseUrl `
        -Path $historyPath `
        -Token $studentTokenAfterRestart).Body
Assert-AcceptanceCondition -Condition ([string]$jobAfterRestart.status -eq 'COMPLETED') -Message 'Completed job was not durable across full Compose restart.'
$analysisAfterRestart = Get-LocalAcceptanceAnalysisRecord -ComposeArguments $compose -JobId $jobId
$snapshotAfterRestart = Get-LocalAcceptanceCurrentSnapshotId `
    -ComposeArguments $compose `
    -CourseId $courseId `
    -StudentId $studentId
Assert-AcceptanceCondition -Condition ([string]$analysisAfterRestart.snapshotId -eq $targetSnapshotId) -Message 'Job target snapshot changed across restart.'
Assert-AcceptanceCondition -Condition ($snapshotAfterRestart -eq $targetSnapshotId) -Message 'Current twin pointer changed across restart.'
Assert-AcceptanceCondition -Condition ([long]$twinAfterRestart.snapshotVersion -eq $snapshotVersion) -Message 'Snapshot version changed across restart.'
Assert-AcceptanceCondition -Condition ([long]$historyAfterRestart.total -eq [long]$historyAfterRecovery.total) -Message 'Full restart duplicated or lost snapshots.'

$report = [ordered]@{
    schemaVersion = 1
    kind = 'edutwin-restart-recovery-verification'
    status = 'PASSED'
    baseUrl = $BaseUrl
    verifiedAt = [DateTimeOffset]::UtcNow.ToString('o')
    courseId = $courseId
    studentId = $studentId
    answerEventId = [string]$analysisAfterRestart.answerEventId
    analysisJobId = $jobId
    snapshotId = $targetSnapshotId
    snapshotVersion = $snapshotVersion
    historyCountBefore = [long]$historyBefore.total
    historyCountAfter = [long]$historyAfterRestart.total
    degradationReasons = @($analysisAfterRestart.degradationReasons)
    recoveredAfterBackendAndRedisRestart = $true
    durableAfterFullComposeRestart = $true
}
Write-AcceptanceEvidence -Value $report -Path $OutputPath
$report
