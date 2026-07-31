[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$BaseUrl,
    [Parameter(Mandatory)][string]$EnvFile,
    [ValidateSet('Local', 'Server')][string]$Scope = 'Local',
    [string]$Server,
    [string]$SshUser,
    [string]$IdentityFile = (Join-Path $HOME '.ssh\id_rsa'),
    [ValidatePattern('^/[A-Za-z0-9._/-]+$')]
    [string]$RemoteRelease = '/opt/edutwin/releases/current',
    [Parameter(Mandatory)][string]$OutputPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($Scope -eq 'Server' -and
    ([string]::IsNullOrWhiteSpace($Server) -or [string]::IsNullOrWhiteSpace($SshUser))) {
    throw 'Server and SshUser are required when Scope is Server.'
}

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
. (Join-Path $repositoryRoot 'tests\support\Acceptance.Http.ps1')
$environment = Read-AcceptanceEnvironment -Path $EnvFile
$teacherPassword = Get-RequiredEnvironmentValue `
    -Environment $environment `
    -Name 'EDUTWIN_DEMO_TEACHER_PASSWORD'
$teacherRecord = Get-DemoTeacherRecord -RepositoryRoot $repositoryRoot
$studentPassword = Get-RequiredEnvironmentValue `
    -Environment $environment `
    -Name 'EDUTWIN_DEMO_STUDENT_PASSWORD'
$compose = @(
    'compose', '--env-file', ([System.IO.Path]::GetFullPath($EnvFile)),
    '-f', (Join-Path $repositoryRoot 'infra\compose\compose.yaml')
)

function Get-AnalysisRecord {
    param([Parameter(Mandatory)][string]$JobId)

    if ($Scope -eq 'Local') {
        return Get-LocalAcceptanceAnalysisRecord -ComposeArguments $compose -JobId $JobId
    }
    $resolvedIdentity = [System.IO.Path]::GetFullPath($IdentityFile)
    if (-not (Test-Path -LiteralPath $resolvedIdentity -PathType Leaf)) {
        throw "Server trace SSH identity file is missing: $resolvedIdentity"
    }
    $sql = Get-AcceptanceAnalysisSql -JobId $JobId
    $remoteScript = @"
set -eu
cd '$RemoteRelease'
cat <<'SQL' | docker compose --env-file infra/env/.env -f infra/compose/compose.yaml exec -T mysql sh -c 'mysql --batch --skip-column-names -u"`$MYSQL_USER" --password="`$MYSQL_PASSWORD" "`$MYSQL_DATABASE"'
$sql
SQL
"@
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($remoteScript.Replace("`r", '')))
    $rows = @(& ssh `
            -o BatchMode=yes `
            -o StrictHostKeyChecking=yes `
            -i $resolvedIdentity `
            "${SshUser}@${Server}" `
            "printf '%s' '$encoded' | base64 -d | bash" 2>$null)
    if ($LASTEXITCODE -ne 0 -or $rows.Count -ne 1) {
        throw "Server analysis query returned $($rows.Count) rows for $JobId."
    }
    return ConvertFrom-AcceptanceAnalysisRow -Row ([string]$rows[0])
}

$teacher = Connect-EduTwinUser `
    -BaseUrl $BaseUrl `
    -Username ([string]$teacherRecord.username) `
    -Password $teacherPassword
$teacherToken = [string]$teacher.accessToken
$courses = Invoke-AcceptanceHttp -BaseUrl $BaseUrl -Path '/api/v1/courses' -Token $teacherToken
Assert-AcceptanceCondition -Condition (@($courses.Body.items).Count -gt 0) -Message 'Teacher course list is empty.'

$highRiskCandidates = [System.Collections.Generic.List[object]]::new()
$dashboardByCourse = @{}
foreach ($course in $courses.Body.items) {
    $courseId = [string]$course.courseId
    $response = Invoke-AcceptanceHttp `
        -BaseUrl $BaseUrl `
        -Path "/api/v1/teacher/courses/$courseId/dashboard" `
        -Token $teacherToken
    $dashboardByCourse[$courseId] = $response.Body
    foreach ($student in $response.Body.students) {
        if ([string]$student.riskBand -eq 'HIGH') {
            $highRiskCandidates.Add([pscustomobject]@{
                courseId = $courseId
                studentId = [string]$student.studentId
                riskProbability = [double]$student.riskProbability
            })
        }
    }
}
Assert-AcceptanceCondition `
    -Condition ($highRiskCandidates.Count -gt 0) `
    -Message 'No HIGH-risk student is available for deterministic closed-loop acceptance.'
$selected = @($highRiskCandidates | Sort-Object `
        @{ Expression = 'riskProbability'; Descending = $true }, `
        @{ Expression = 'studentId'; Descending = $false })[0]
$courseId = [string]$selected.courseId
$studentId = [string]$selected.studentId
$dashboardBefore = $dashboardByCourse[$courseId]

$studentRecord = Get-DemoStudentRecord -RepositoryRoot $repositoryRoot -StudentId $studentId
$student = Connect-EduTwinUser `
    -BaseUrl $BaseUrl `
    -Username ([string]$studentRecord.username) `
    -Password $studentPassword
$studentToken = [string]$student.accessToken
Assert-AcceptanceCondition `
    -Condition ([string]$student.user.userId -eq $studentId) `
    -Message 'Selected dashboard student does not match the authenticated student account.'

$twinPath = "/api/v1/courses/$courseId/students/$studentId/twin/current"
$historyPath = "/api/v1/courses/$courseId/students/$studentId/twin/history?limit=100"
$planPath = "/api/v1/courses/$courseId/students/$studentId/learning-plans/current"
$beforeTwin = (Invoke-AcceptanceHttp -BaseUrl $BaseUrl -Path $twinPath -Token $studentToken).Body
$beforeHistory = (Invoke-AcceptanceHttp -BaseUrl $BaseUrl -Path $historyPath -Token $studentToken).Body
$beforePlan = (Invoke-AcceptanceHttp -BaseUrl $BaseUrl -Path $planPath -Token $studentToken).Body
$question = (Invoke-AcceptanceHttp `
        -BaseUrl $BaseUrl `
        -Path "/api/v1/courses/$courseId/practice/next" `
        -Token $studentToken).Body
$correctAnswer = Get-DemoCorrectAnswer `
    -RepositoryRoot $repositoryRoot `
    -QuestionId ([string]$question.questionId)
Assert-AcceptanceCondition `
    -Condition ($correctAnswer -in @($question.choices | ForEach-Object { [string]$_.choiceId })) `
    -Message 'Generated correct answer is absent from the public practice choices.'
$targetSkillId = [string]@($question.skillIds)[0]
$beforeSkill = @($beforeTwin.mastery | Where-Object { [string]$_.skillId -eq $targetSkillId })
Assert-AcceptanceCondition `
    -Condition ($beforeSkill.Count -eq 1) `
    -Message 'The practice target skill is absent from the current twin mastery vector.'

$payload = [ordered]@{
    questionId = [string]$question.questionId
    selectedChoiceId = $correctAnswer
    occurredAt = [DateTimeOffset]::UtcNow.AddMilliseconds(-100).ToString('o')
}
$idempotencyKey = "acceptance-$($Scope.ToLowerInvariant())-$([Guid]::NewGuid().ToString('N'))"
$endToEnd = [System.Diagnostics.Stopwatch]::StartNew()
$submitted = Invoke-AcceptanceHttp `
    -BaseUrl $BaseUrl `
    -Path "/api/v1/courses/$courseId/answers" `
    -Method POST `
    -Token $studentToken `
    -Headers @{ 'Idempotency-Key' = $idempotencyKey } `
    -Body $payload `
    -ExpectedStatus @(202)
if ($Scope -eq 'Local') {
    Assert-AcceptanceCondition `
        -Condition ($submitted.ElapsedMs -lt 500) `
        -Message "Answer transaction latency bound failed for the acceptance sample: $($submitted.ElapsedMs) ms."
}
$jobId = [string]$submitted.Body.jobId
Assert-AcceptanceCondition `
    -Condition ([string]$submitted.Body.status -eq 'QUEUED') `
    -Message 'Answer submission waited for analysis instead of returning a QUEUED job.'
Assert-AcceptanceCondition `
    -Condition (-not [string]::IsNullOrWhiteSpace($jobId)) `
    -Message 'Queued analysis job lacks an answer event identifier.'

$sse = Wait-EduTwinSseTerminal `
    -BaseUrl $BaseUrl `
    -Token $studentToken `
    -JobId $jobId `
    -TimeoutSeconds 10
$endToEnd.Stop()
Assert-AcceptanceCondition `
    -Condition ($endToEnd.ElapsedMilliseconds -lt 5000) `
    -Message "Event-to-SSE completion exceeded 5 seconds: $($endToEnd.ElapsedMilliseconds) ms."
Assert-AcceptanceCondition `
    -Condition ([string]$sse.TerminalJob.status -eq 'COMPLETED') `
    -Message "Analysis job did not complete successfully: $($sse.TerminalJob.status)"
$eventNames = @($sse.Events | ForEach-Object { [string]$_.eventType })
foreach ($requiredEvent in @(
        'job.queued',
        'job.processing',
        'predictions.computed',
        'twin.snapshot-created',
        'learning-plan.created',
        'job.completed'
    )) {
    Assert-AcceptanceCondition `
        -Condition ($requiredEvent -in $eventNames) `
        -Message "SSE lifecycle omitted required event: $requiredEvent"
}
$resumeAfter = [long]$sse.TerminalJob.lastEventSequence - 1
Assert-AcceptanceCondition -Condition ($resumeAfter -gt 0) -Message 'Completed job lacks a resumable SSE sequence.'
$resumed = Wait-EduTwinSseTerminal `
    -BaseUrl $BaseUrl `
    -Token $studentToken `
    -JobId $jobId `
    -LastEventId $resumeAfter `
    -TimeoutSeconds 5
Assert-AcceptanceCondition `
    -Condition (@($resumed.Events).Count -eq 1 -and
        [long]$resumed.Events[0].sequence -eq [long]$sse.TerminalJob.lastEventSequence -and
        [string]$resumed.Events[0].eventType -eq 'job.completed') `
    -Message 'Last-Event-ID recovery did not return only the persisted terminal event.'

$replay = Invoke-AcceptanceHttp `
    -BaseUrl $BaseUrl `
    -Path "/api/v1/courses/$courseId/answers" `
    -Method POST `
    -Token $studentToken `
    -Headers @{ 'Idempotency-Key' = $idempotencyKey } `
    -Body $payload `
    -ExpectedStatus @(202)
Assert-AcceptanceCondition `
    -Condition ([string]$replay.Body.jobId -eq $jobId) `
    -Message 'Idempotent replay created a different analysis job.'
Assert-AcceptanceCondition `
    -Condition ('true' -in @($replay.Headers['Idempotency-Replayed'])) `
    -Message 'Idempotent replay response did not expose Idempotency-Replayed=true.'

$conflictingPayload = [ordered]@{
    questionId = [string]$payload.questionId
    selectedChoiceId = [string]$payload.selectedChoiceId
    occurredAt = [DateTimeOffset]::Parse([string]$payload.occurredAt).AddSeconds(-1).ToString('o')
}
$conflict = Invoke-AcceptanceHttp `
    -BaseUrl $BaseUrl `
    -Path "/api/v1/courses/$courseId/answers" `
    -Method POST `
    -Token $studentToken `
    -Headers @{ 'Idempotency-Key' = $idempotencyKey } `
    -Body $conflictingPayload `
    -ExpectedStatus @(409)
Assert-AcceptanceCondition `
    -Condition ([string]$conflict.Body.code -eq 'IDEMPOTENCY_KEY_REUSED') `
    -Message 'Idempotency conflict did not return the fixed domain code.'

$afterTwin = (Invoke-AcceptanceHttp -BaseUrl $BaseUrl -Path $twinPath -Token $studentToken).Body
$afterHistory = (Invoke-AcceptanceHttp -BaseUrl $BaseUrl -Path $historyPath -Token $studentToken).Body
$afterPlan = (Invoke-AcceptanceHttp -BaseUrl $BaseUrl -Path $planPath -Token $studentToken).Body
$dashboardAfter = (Invoke-AcceptanceHttp `
        -BaseUrl $BaseUrl `
        -Path "/api/v1/teacher/courses/$courseId/dashboard" `
        -Token $teacherToken).Body
$analysis = Get-AnalysisRecord -JobId $jobId
$afterSkill = @($afterTwin.mastery | Where-Object { [string]$_.skillId -eq $targetSkillId })
Assert-AcceptanceCondition -Condition ($afterSkill.Count -eq 1) -Message 'Updated twin lost the target skill.'

$numericChanges = [ordered]@{
    mastery = [ordered]@{
        before = [double]$beforeSkill[0].probability
        after = [double]$afterSkill[0].probability
    }
    nextCorrectProbability = [ordered]@{
        before = [double]$beforeTwin.nextCorrectProbability
        after = [double]$afterTwin.nextCorrectProbability
    }
    riskProbability = [ordered]@{
        before = [double]$beforeTwin.risk.probability
        after = [double]$afterTwin.risk.probability
    }
    snapshotVersion = [ordered]@{
        before = [long]$beforeTwin.snapshotVersion
        after = [long]$afterTwin.snapshotVersion
    }
    snapshotCount = [ordered]@{
        before = [long]$beforeHistory.total
        after = [long]$afterHistory.total
    }
    planVersion = [ordered]@{
        before = [long]$beforePlan.version
        after = [long]$afterPlan.version
    }
    teacherAverageRisk = [ordered]@{
        before = [double]$dashboardBefore.averageRiskProbability
        after = [double]$dashboardAfter.averageRiskProbability
    }
}
Assert-AcceptanceCondition -Condition ($numericChanges.mastery.after -ne $numericChanges.mastery.before) -Message 'Target mastery did not change numerically.'
Assert-AcceptanceCondition -Condition ($numericChanges.nextCorrectProbability.after -ne $numericChanges.nextCorrectProbability.before) -Message 'Next-correct probability did not change numerically.'
Assert-AcceptanceCondition -Condition ($numericChanges.riskProbability.after -ne $numericChanges.riskProbability.before) -Message 'Risk probability did not change numerically.'
Assert-AcceptanceCondition -Condition ($numericChanges.snapshotVersion.after -gt $numericChanges.snapshotVersion.before) -Message 'Snapshot version did not advance.'
Assert-AcceptanceCondition -Condition ($numericChanges.snapshotCount.after -gt $numericChanges.snapshotCount.before) -Message 'Immutable snapshot history did not grow.'
Assert-AcceptanceCondition -Condition ($numericChanges.planVersion.after -gt $numericChanges.planVersion.before) -Message 'Learning plan version did not advance.'
Assert-AcceptanceCondition -Condition ($numericChanges.teacherAverageRisk.after -ne $numericChanges.teacherAverageRisk.before) -Message 'Teacher risk aggregate did not change numerically.'
Assert-AcceptanceCondition `
    -Condition ([string]$analysis.status -eq 'COMPLETED' -and
        -not [string]::IsNullOrWhiteSpace([string]$analysis.answerEventId) -and
        -not [string]::IsNullOrWhiteSpace([string]$analysis.snapshotId) -and
        [int]$analysis.snapshotLinkCount -eq 1 -and
        [int]$analysis.planLinkCount -eq 1) `
    -Message 'Answer, job, snapshot, twin, and plan trace identifiers are not continuous.'

$report = [ordered]@{
    schemaVersion = 1
    kind = 'edutwin-closed-loop-verification'
    status = 'PASSED'
    scope = $Scope
    baseUrl = $BaseUrl
    verifiedAt = [DateTimeOffset]::UtcNow.ToString('o')
    selection = [ordered]@{
        rule = 'highest HIGH riskProbability, then studentId ascending'
        courseId = $courseId
        studentId = $studentId
        username = [string]$studentRecord.username
        initialDashboardRiskProbability = [double]$selected.riskProbability
        questionId = [string]$question.questionId
        targetSkillId = $targetSkillId
    }
    trace = [ordered]@{
        idempotencyKey = $idempotencyKey
        answerEventId = [string]$analysis.answerEventId
        analysisJobId = $jobId
        snapshotId = [string]$analysis.snapshotId
        planId = [string]$afterPlan.planId
    }
    latency = [ordered]@{
        answerTransactionMs = [long]$submitted.ElapsedMs
        eventToSseCompletionMs = [long]$endToEnd.ElapsedMilliseconds
    }
    sseEvents = $eventNames
    sseResume = [ordered]@{
        lastEventId = $resumeAfter
        returnedSequences = @($resumed.Events | ForEach-Object { [long]$_.sequence })
        terminalEvent = [string]$resumed.Events[0].eventType
    }
    idempotency = [ordered]@{
        replayStatus = [int]$replay.StatusCode
        replayJobId = [string]$replay.Body.jobId
        conflictStatus = [int]$conflict.StatusCode
        conflictCode = [string]$conflict.Body.code
    }
    numericChanges = $numericChanges
}
Write-AcceptanceEvidence -Value $report -Path $OutputPath
$report
