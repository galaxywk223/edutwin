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
Get-RequiredEnvironmentValue -Environment $environment -Name 'REDIS_PASSWORD' | Out-Null
$compose = @('compose', '--env-file', ([System.IO.Path]::GetFullPath($EnvFile)), '-f', $composeFile)
Set-AcceptanceComposeImageTag -ComposeArguments $compose | Out-Null
$checks = [System.Collections.Generic.List[object]]::new()

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
            $inspect = @(& docker inspect $containerId | ConvertFrom-Json)[0]
            $state = if ($null -ne $inspect.State.Health) {
                [string]$inspect.State.Health.Status
            }
            else {
                [string]$inspect.State.Status
            }
            if ($state -in @('healthy', 'running')) {
                return
            }
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

function Add-ResilienceCheck {
    param([string]$Name, [object]$Evidence)
    $checks.Add([ordered]@{ name = $Name; status = 'PASSED'; evidence = $Evidence })
}

$teacher = Connect-EduTwinUser `
    -BaseUrl $BaseUrl `
    -Username ([string]$teacherRecord.username) `
    -Password $teacherPassword
$teacherToken = [string]$teacher.accessToken
$courses = (Invoke-AcceptanceHttp -BaseUrl $BaseUrl -Path '/api/v1/courses' -Token $teacherToken).Body
$candidates = [System.Collections.Generic.List[object]]::new()
foreach ($course in $courses.items) {
    $courseIdValue = [string]$course.courseId
    $dashboard = (Invoke-AcceptanceHttp `
            -BaseUrl $BaseUrl `
            -Path "/api/v1/teacher/courses/$courseIdValue/dashboard" `
            -Token $teacherToken).Body
    foreach ($entry in $dashboard.students) {
        $candidates.Add([pscustomobject]@{
            courseId = $courseIdValue
            studentId = [string]$entry.studentId
            riskProbability = [double]$entry.riskProbability
        })
    }
}
Assert-AcceptanceCondition -Condition ($candidates.Count -gt 0) -Message 'No student is available for resilience verification.'
$selected = @($candidates | Sort-Object `
        @{ Expression = 'riskProbability'; Descending = $true }, `
        @{ Expression = 'studentId'; Descending = $false })[0]
$courseId = [string]$selected.courseId
$studentId = [string]$selected.studentId
$studentRecord = Get-DemoStudentRecord -RepositoryRoot $repositoryRoot -StudentId $studentId
$student = Connect-EduTwinUser `
    -BaseUrl $BaseUrl `
    -Username ([string]$studentRecord.username) `
    -Password $studentPassword
$studentToken = [string]$student.accessToken

function Submit-CorrectAnswer {
    param([string]$Scenario)
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
    $key = "acceptance-resilience-$Scenario-$([Guid]::NewGuid().ToString('N'))"
    $response = Invoke-AcceptanceHttp `
        -BaseUrl $BaseUrl `
        -Path "/api/v1/courses/$courseId/answers" `
        -Method POST `
        -Token $studentToken `
        -Headers @{ 'Idempotency-Key' = $key } `
        -Body $payload `
        -ExpectedStatus @(202)
    return [pscustomobject]@{ Response = $response; Payload = $payload; IdempotencyKey = $key }
}

$modelStopped = $false
try {
    Invoke-ComposeChecked -Arguments @('stop', '--timeout', '20', 'model-service') | Out-Null
    $modelStopped = $true
    $fallbackSubmission = Submit-CorrectAnswer -Scenario 'model-down'
    $fallbackJobId = [string]$fallbackSubmission.Response.Body.jobId
    $fallbackSse = Wait-EduTwinSseTerminal `
        -BaseUrl $BaseUrl `
        -Token $studentToken `
        -JobId $fallbackJobId `
        -TimeoutSeconds 15
    Assert-AcceptanceCondition -Condition ([string]$fallbackSse.TerminalJob.status -eq 'COMPLETED') -Message 'Model-service outage did not complete through fallback.'
    $fallbackAnalysis = Get-LocalAcceptanceAnalysisRecord -ComposeArguments $compose -JobId $fallbackJobId
    foreach ($reason in @('RISK_MODEL_UNAVAILABLE', 'MODEL_CONTRACT_ERROR')) {
        Assert-AcceptanceCondition `
            -Condition ($reason -in @($fallbackAnalysis.degradationReasons)) `
            -Message "Model-service outage did not record degradation reason: $reason"
    }
    Assert-AcceptanceCondition `
        -Condition (@($fallbackAnalysis.effectiveModelVersions | Where-Object family -eq 'RULE').Count -gt 0) `
        -Message 'Fallback twin does not expose rule mastery estimates.'
    Add-ResilienceCheck -Name 'model-service-rule-fallback' -Evidence ([ordered]@{
            analysisJobId = $fallbackJobId
            snapshotId = [string]$fallbackAnalysis.snapshotId
            degradationReasons = @($fallbackAnalysis.degradationReasons)
        })
}
finally {
    if ($modelStopped) {
        Invoke-ComposeChecked -Arguments @('start', 'model-service') | Out-Null
        Wait-ComposeHealthy -Service 'model-service'
    }
}

$redisStopped = $false
try {
    Invoke-ComposeChecked -Arguments @('stop', '--timeout', '10', 'redis') | Out-Null
    $redisStopped = $true
    $redisSubmission = Submit-CorrectAnswer -Scenario 'redis-down'
    $redisJobId = [string]$redisSubmission.Response.Body.jobId
    Wait-OutboxAttempt -JobId $redisJobId
    Invoke-ComposeChecked -Arguments @('start', 'redis') | Out-Null
    $redisStopped = $false
    Wait-ComposeHealthy -Service 'redis'
    $redisSse = Wait-EduTwinSseTerminal `
        -BaseUrl $BaseUrl `
        -Token $studentToken `
        -JobId $redisJobId `
        -TimeoutSeconds 20
    Assert-AcceptanceCondition -Condition ([string]$redisSse.TerminalJob.status -eq 'COMPLETED') -Message 'Redis retry did not recover the analysis job.'
    $redisAnalysis = Get-LocalAcceptanceAnalysisRecord -ComposeArguments $compose -JobId $redisJobId
    Assert-AcceptanceCondition `
        -Condition ('REDIS_RETRY' -in @($redisAnalysis.degradationReasons)) `
        -Message 'Recovered Redis delivery did not retain REDIS_RETRY.'
    Add-ResilienceCheck -Name 'redis-outbox-retry' -Evidence ([ordered]@{
            analysisJobId = $redisJobId
            degradationReasons = @($redisAnalysis.degradationReasons)
        })
}
finally {
    if ($redisStopped) {
        Invoke-ComposeChecked -Arguments @('start', 'redis') | Out-Null
        Wait-ComposeHealthy -Service 'redis'
    }
}

$twinPath = "/api/v1/courses/$courseId/students/$studentId/twin/current"
$dashboardPath = "/api/v1/teacher/courses/$courseId/dashboard?period=30D"
$jobPath = "/api/v1/analysis/jobs/$redisJobId"
$cachedTwin = (Invoke-AcceptanceHttp -BaseUrl $BaseUrl -Path $twinPath -Token $studentToken).Body
$cachedDashboard = (Invoke-AcceptanceHttp -BaseUrl $BaseUrl -Path $dashboardPath -Token $teacherToken).Body
$cachedJob = (Invoke-AcceptanceHttp -BaseUrl $BaseUrl -Path $jobPath -Token $studentToken).Body
$cacheKeys = @(
    "twin:$studentId`:current",
    "course:$courseId`:30D:analytics",
    "job:$redisJobId"
)
$quotedKeys = $cacheKeys | ForEach-Object { "'" + $_.Replace("'", "'\\''") + "'" }
$deleteCommand = 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --no-auth-warning DEL ' + ($quotedKeys -join ' ')
$deleted = Invoke-ComposeChecked -Arguments @('exec', '-T', 'redis', 'sh', '-c', $deleteCommand)
Assert-AcceptanceCondition -Condition ([int]($deleted | Select-Object -Last 1) -eq 3) -Message 'Expected Redis cache keys were not all present before invalidation.'
Invoke-ComposeChecked -Arguments @('restart', '--timeout', '20', 'backend') | Out-Null
Wait-ComposeHealthy -Service 'backend'
$rebuiltTwin = (Invoke-AcceptanceHttp -BaseUrl $BaseUrl -Path $twinPath -Token $studentToken).Body
$rebuiltDashboard = (Invoke-AcceptanceHttp -BaseUrl $BaseUrl -Path $dashboardPath -Token $teacherToken).Body
$rebuiltJob = (Invoke-AcceptanceHttp -BaseUrl $BaseUrl -Path $jobPath -Token $studentToken).Body
Assert-AcceptanceCondition `
    -Condition ([long]$rebuiltTwin.snapshotVersion -eq [long]$cachedTwin.snapshotVersion -and
        [double]$rebuiltTwin.risk.probability -eq [double]$cachedTwin.risk.probability) `
    -Message 'Twin cache rebuild differs from MySQL source state.'
Assert-AcceptanceCondition `
    -Condition ([double]$rebuiltDashboard.averageRiskProbability -eq [double]$cachedDashboard.averageRiskProbability) `
    -Message 'Dashboard cache rebuild differs from MySQL source state.'
Assert-AcceptanceCondition `
    -Condition ([string]$rebuiltJob.status -eq [string]$cachedJob.status) `
    -Message 'Job cache rebuild differs from MySQL source state.'
$existsCommand = 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --no-auth-warning EXISTS ' + ($quotedKeys -join ' ')
$exists = Invoke-ComposeChecked -Arguments @('exec', '-T', 'redis', 'sh', '-c', $existsCommand)
Assert-AcceptanceCondition -Condition ([int]($exists | Select-Object -Last 1) -eq 3) -Message 'MySQL-backed reads did not repopulate all fixed Redis projections.'
Add-ResilienceCheck -Name 'cache-invalidation-mysql-rebuild' -Evidence ([ordered]@{
        keys = $cacheKeys
        snapshotId = [string]$redisAnalysis.snapshotId
        jobStatus = [string]$rebuiltJob.status
    })

$snapshotId = Get-LocalAcceptanceCurrentSnapshotId `
    -ComposeArguments $compose `
    -CourseId $courseId `
    -StudentId $studentId
$mutationCommands = @(
    [ordered]@{
        operation = 'UPDATE'
        sql = "UPDATE twin_snapshot SET snapshot_version = snapshot_version WHERE id = '$snapshotId';"
    },
    [ordered]@{
        operation = 'DELETE'
        sql = "DELETE FROM twin_snapshot WHERE id = '$snapshotId';"
    }
)
$immutableEvidence = @()
foreach ($mutation in $mutationCommands) {
    $command = 'mysql --batch --skip-column-names -u"$MYSQL_USER" --password="$MYSQL_PASSWORD" "$MYSQL_DATABASE" -e "' + [string]$mutation.sql + '"'
    $output = & docker @compose exec -T mysql sh -c $command 2>&1
    $exitCode = $LASTEXITCODE
    Assert-AcceptanceCondition -Condition ($exitCode -ne 0) -Message "$($mutation.operation) unexpectedly modified an immutable snapshot."
    Assert-AcceptanceCondition `
        -Condition (($output -join ' ') -match 'twin_snapshot is immutable') `
        -Message "$($mutation.operation) failed without the immutable snapshot trigger."
    $immutableEvidence += [ordered]@{ operation = $mutation.operation; exitCode = $exitCode }
}
$postMutationTwin = (Invoke-AcceptanceHttp -BaseUrl $BaseUrl -Path $twinPath -Token $studentToken).Body
$postMutationSnapshotId = Get-LocalAcceptanceCurrentSnapshotId `
    -ComposeArguments $compose `
    -CourseId $courseId `
    -StudentId $studentId
Assert-AcceptanceCondition `
    -Condition ($postMutationSnapshotId -eq $snapshotId -and
        [long]$postMutationTwin.snapshotVersion -eq [long]$rebuiltTwin.snapshotVersion) `
    -Message 'Immutable snapshot disappeared after rejected mutation attempts.'
Add-ResilienceCheck -Name 'immutable-snapshot-update-delete-rejected' -Evidence $immutableEvidence

Assert-AcceptanceCondition `
    -Condition ([string]$environment['EDUTWIN_AI_FORCE_TIMEOUT'] -ne 'true') `
    -Message 'LLM timeout injection requires EDUTWIN_AI_FORCE_TIMEOUT=false before the test.'
$priorForceTimeout = $env:EDUTWIN_AI_FORCE_TIMEOUT
$configuredForceTimeout = if ($environment.Contains('EDUTWIN_AI_FORCE_TIMEOUT')) {
    [string]$environment['EDUTWIN_AI_FORCE_TIMEOUT']
}
else {
    'false'
}
$backendInjected = $false
try {
    $env:EDUTWIN_AI_FORCE_TIMEOUT = 'true'
    Invoke-ComposeChecked -Arguments @('up', '-d', '--no-deps', '--force-recreate', 'backend') | Out-Null
    $backendInjected = $true
    Wait-ComposeHealthy -Service 'backend'
    $timeoutSubmission = Submit-CorrectAnswer -Scenario 'llm-timeout'
    $timeoutJobId = [string]$timeoutSubmission.Response.Body.jobId
    $timeoutSse = Wait-EduTwinSseTerminal `
        -BaseUrl $BaseUrl `
        -Token $studentToken `
        -JobId $timeoutJobId `
        -TimeoutSeconds 15
    Assert-AcceptanceCondition -Condition ([string]$timeoutSse.TerminalJob.status -eq 'COMPLETED') -Message 'LLM timeout did not complete through template fallback.'
    $timeoutAnalysis = Get-LocalAcceptanceAnalysisRecord -ComposeArguments $compose -JobId $timeoutJobId
    Assert-AcceptanceCondition `
        -Condition (@(@('LLM_TIMEOUT', 'DEEPSEEK_TIMEOUT') |
                Where-Object { $_ -in @($timeoutAnalysis.degradationReasons) }).Count -gt 0) `
        -Message 'Forced LLM timeout did not record a compatible timeout degradation reason.'
    $timeoutDiagnosis = (Invoke-AcceptanceHttp `
            -BaseUrl $BaseUrl `
            -Path "/api/v1/courses/$courseId/students/$studentId/diagnoses" `
            -Method POST `
            -Token $studentToken `
            -Body ([ordered]@{})).Body
    Assert-AcceptanceCondition `
        -Condition ([bool]$timeoutAnalysis.diagnosisDegraded -and
            [string]$timeoutAnalysis.diagnosisProvider -eq 'TEMPLATE' -and
            [string]$timeoutAnalysis.diagnosisModel -eq 'template-diagnosis-v1' -and
            -not [bool]$timeoutAnalysis.toolCallVerified) `
        -Message 'Forced LLM timeout did not return the fixed template diagnosis structure.'
    Assert-AcceptanceCondition -Condition (@($timeoutDiagnosis.evidence).Count -eq 5) -Message 'Template diagnosis does not preserve five evidence factors.'
    Assert-AcceptanceCondition -Condition (@($timeoutDiagnosis.learningPlan.tasks).Count -gt 0) -Message 'Template diagnosis does not preserve the rule plan.'
    Add-ResilienceCheck -Name 'llm-timeout-template-fallback' -Evidence ([ordered]@{
            analysisJobId = $timeoutJobId
            diagnosisId = [string]$timeoutDiagnosis.diagnosisId
            providerModel = [string]$timeoutAnalysis.diagnosisModel
            degradationReasons = @($timeoutAnalysis.degradationReasons)
        })
}
finally {
    if ($backendInjected) {
        $env:EDUTWIN_AI_FORCE_TIMEOUT = $configuredForceTimeout
        Invoke-ComposeChecked -Arguments @('up', '-d', '--no-deps', '--force-recreate', 'backend') | Out-Null
        Wait-ComposeHealthy -Service 'backend'
    }
    if ($null -eq $priorForceTimeout) {
        Remove-Item Env:EDUTWIN_AI_FORCE_TIMEOUT -ErrorAction SilentlyContinue
    }
    else {
        $env:EDUTWIN_AI_FORCE_TIMEOUT = $priorForceTimeout
    }
}

$report = [ordered]@{
    schemaVersion = 1
    kind = 'edutwin-resilience-verification'
    status = 'PASSED'
    baseUrl = $BaseUrl
    verifiedAt = [DateTimeOffset]::UtcNow.ToString('o')
    courseId = $courseId
    studentId = $studentId
    checks = @($checks)
}
Write-AcceptanceEvidence -Value $report -Path $OutputPath
$report
