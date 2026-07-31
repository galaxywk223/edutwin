[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$BaseUrl,
    [Parameter(Mandatory)][string]$EnvFile,
    [Parameter(Mandatory)][string]$OutputPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
. (Join-Path $repositoryRoot 'tests\support\Acceptance.Http.ps1')
$environment = Read-AcceptanceEnvironment -Path $EnvFile
$teacherPassword = Get-RequiredEnvironmentValue -Environment $environment -Name 'EDUTWIN_DEMO_TEACHER_PASSWORD'
$teacherRecord = Get-DemoTeacherRecord -RepositoryRoot $repositoryRoot
$studentPassword = Get-RequiredEnvironmentValue -Environment $environment -Name 'EDUTWIN_DEMO_STUDENT_PASSWORD'
$adminUsername = Get-RequiredEnvironmentValue -Environment $environment -Name 'EDUTWIN_ADMIN_USERNAME'
$adminPassword = Get-RequiredEnvironmentValue -Environment $environment -Name 'EDUTWIN_ADMIN_PASSWORD'
$compose = @(
    'compose', '--env-file', ([System.IO.Path]::GetFullPath($EnvFile)),
    '-f', (Join-Path $repositoryRoot 'infra\compose\compose.yaml')
)
Set-AcceptanceComposeImageTag -ComposeArguments $compose | Out-Null

function Set-LlmTimeout {
    param([Parameter(Mandatory)][string]$Timeout)

    $env:EDUTWIN_AI_TIMEOUT = $Timeout
    & docker @compose up -d --no-deps --force-recreate backend | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Backend recreation failed for LLM timeout $Timeout."
    }
    $deadline = [DateTimeOffset]::UtcNow.AddMinutes(3)
    do {
        $containerId = [string](& docker @compose ps -q backend | Select-Object -First 1)
        if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($containerId)) {
            $container = @(& docker inspect $containerId | ConvertFrom-Json)[0]
            if ([string]$container.State.Health.Status -eq 'healthy') {
                return
            }
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw "Backend did not become healthy for LLM timeout $Timeout."
}
Assert-AcceptanceCondition `
    -Condition ([string]$environment['EDUTWIN_AI_FORCE_TIMEOUT'] -ne 'true') `
    -Message 'EDUTWIN_AI_FORCE_TIMEOUT must be false for the real LLM acceptance gate.'

$admin = Connect-EduTwinUser -BaseUrl $BaseUrl -Username $adminUsername -Password $adminPassword
$runtimeConfiguration = (Invoke-AcceptanceHttp `
        -BaseUrl $BaseUrl `
        -Path '/api/v1/admin/ai-configuration' `
        -Token ([string]$admin.accessToken)).Body
Assert-AcceptanceCondition `
    -Condition ([bool]$runtimeConfiguration.enabled) `
    -Message 'The effective runtime AI configuration must be enabled for the real LLM acceptance gate.'
Assert-AcceptanceCondition `
    -Condition ([bool]$runtimeConfiguration.apiKeyConfigured) `
    -Message 'The effective runtime AI configuration must have a write-only API key.'
Assert-AcceptanceCondition `
    -Condition (-not [string]::IsNullOrWhiteSpace([string]$runtimeConfiguration.model)) `
    -Message 'The effective runtime AI configuration must identify a model.'

$originalTimeout = $env:EDUTWIN_AI_TIMEOUT
$configuredTimeout = if ($environment.Contains('EDUTWIN_AI_TIMEOUT')) {
    [string]$environment['EDUTWIN_AI_TIMEOUT']
}
else {
    'PT2S'
}
$report = $null
try {
Set-LlmTimeout -Timeout 'PT30S'
$teacher = Connect-EduTwinUser `
    -BaseUrl $BaseUrl `
    -Username ([string]$teacherRecord.username) `
    -Password $teacherPassword
$teacherToken = [string]$teacher.accessToken
$courses = (Invoke-AcceptanceHttp -BaseUrl $BaseUrl -Path '/api/v1/courses' -Token $teacherToken).Body
$candidates = [System.Collections.Generic.List[object]]::new()
foreach ($course in $courses.items) {
    $courseId = [string]$course.courseId
    $dashboard = (Invoke-AcceptanceHttp `
            -BaseUrl $BaseUrl `
            -Path "/api/v1/teacher/courses/$courseId/dashboard" `
            -Token $teacherToken).Body
    foreach ($entry in $dashboard.students) {
        $candidates.Add([pscustomobject]@{
            courseId = $courseId
            studentId = [string]$entry.studentId
            riskProbability = [double]$entry.riskProbability
        })
    }
}
Assert-AcceptanceCondition -Condition ($candidates.Count -gt 0) -Message 'No student is available for DeepSeek verification.'
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
$question = (Invoke-AcceptanceHttp `
        -BaseUrl $BaseUrl `
        -Path "/api/v1/courses/$courseId/practice/next" `
        -Token $studentToken).Body
$correctAnswer = Get-DemoCorrectAnswer -RepositoryRoot $repositoryRoot -QuestionId ([string]$question.questionId)
$payload = [ordered]@{
    questionId = [string]$question.questionId
    selectedChoiceId = $correctAnswer
    occurredAt = [DateTimeOffset]::UtcNow.AddMilliseconds(-100).ToString('o')
}
$idempotencyKey = "acceptance-deepseek-$([Guid]::NewGuid().ToString('N'))"
$submitted = Invoke-AcceptanceHttp `
    -BaseUrl $BaseUrl `
    -Path "/api/v1/courses/$courseId/answers" `
    -Method POST `
    -Token $studentToken `
    -Headers @{ 'Idempotency-Key' = $idempotencyKey } `
    -Body $payload `
    -ExpectedStatus @(202)
$jobId = [string]$submitted.Body.jobId
$sse = Wait-EduTwinSseTerminal `
    -BaseUrl $BaseUrl `
    -Token $studentToken `
    -JobId $jobId `
    -TimeoutSeconds 60
Assert-AcceptanceCondition `
    -Condition ([string]$sse.TerminalJob.status -eq 'COMPLETED') `
    -Message 'LLM-triggering analysis job did not complete.'
Assert-AcceptanceCondition `
    -Condition ([string]$sse.TerminalJob.status -eq 'COMPLETED') `
    -Message 'LLM-triggering analysis job used a diagnosis fallback.'

$twin = (Invoke-AcceptanceHttp `
        -BaseUrl $BaseUrl `
        -Path "/api/v1/courses/$courseId/students/$studentId/twin/current" `
        -Token $studentToken).Body
$analysis = Get-LocalAcceptanceAnalysisRecord -ComposeArguments $compose -JobId $jobId
$diagnosis = (Invoke-AcceptanceHttp `
        -BaseUrl $BaseUrl `
        -Path "/api/v1/courses/$courseId/students/$studentId/diagnoses" `
        -Method POST `
        -Token $studentToken `
        -Body ([ordered]@{})).Body
Assert-AcceptanceCondition `
    -Condition (-not [bool]$analysis.diagnosisDegraded -and
        @($analysis.degradationReasons).Count -eq 0) `
    -Message 'Diagnosis generation mode is not a verified LLM tool call.'
Assert-AcceptanceCondition `
    -Condition ([string]$analysis.diagnosisProvider -in @('OPENAI_COMPATIBLE', 'DEEPSEEK') -and
        [string]$analysis.diagnosisModel -eq [string]$runtimeConfiguration.model) `
    -Message 'Diagnosis provider model does not match the effective runtime AI configuration.'
Assert-AcceptanceCondition `
    -Condition ([bool]$analysis.toolCallVerified) `
    -Message 'Diagnosis did not record a verified tool call.'
Assert-AcceptanceCondition `
    -Condition (@($diagnosis.evidence).Count -eq 5) `
    -Message 'LLM diagnosis does not contain exactly five SHAP factors.'
Assert-AcceptanceCondition `
    -Condition ([string]$analysis.diagnosisId -eq [string]$diagnosis.diagnosisId -and
        [int]$analysis.snapshotLinkCount -eq 1 -and
        [int]$analysis.planLinkCount -eq 1) `
    -Message 'LLM diagnosis is not bound to the triggering job and snapshot.'
$riskModels = @($analysis.effectiveModelVersions | Where-Object purpose -eq 'RISK')
Assert-AcceptanceCondition -Condition ($riskModels.Count -eq 1) -Message 'Twin trace does not bind one effective risk model.'
$riskVersion = [string]$riskModels[0].modelVersion
$ranks = @($diagnosis.evidence | ForEach-Object { [int]$_.rank })
$features = @($diagnosis.evidence | ForEach-Object { [string]$_.featureName })
Assert-AcceptanceCondition `
    -Condition (($ranks -join ',') -eq '1,2,3,4,5' -and @($features | Select-Object -Unique).Count -eq 5) `
    -Message 'LLM diagnosis evidence is not a unique ordered top-five set.'
Assert-AcceptanceCondition `
    -Condition ([int]$analysis.evidenceRiskModelCount -eq 1 -and
        [string]$analysis.evidenceRiskModelVersion -eq $riskVersion) `
    -Message 'LLM diagnosis includes evidence from a different risk model version.'

$report = [ordered]@{
    schemaVersion = 1
    kind = 'edutwin-openai-compatible-tool-call-verification'
    status = 'PASSED'
    baseUrl = $BaseUrl
    verifiedAt = [DateTimeOffset]::UtcNow.ToString('o')
    providerModel = [string]$analysis.diagnosisModel
    generationMode = if ([string]$analysis.diagnosisProvider -eq 'DEEPSEEK') {
        'DEEPSEEK_TOOL_CALL'
    }
    else {
        'LLM_TOOL_CALL'
    }
    toolCallVerified = [bool]$analysis.toolCallVerified
    courseId = $courseId
    studentId = $studentId
    answerEventId = [string]$analysis.answerEventId
    analysisJobId = $jobId
    snapshotId = [string]$analysis.snapshotId
    diagnosisId = [string]$diagnosis.diagnosisId
    riskModelVersion = $riskVersion
    evidence = @($diagnosis.evidence | ForEach-Object {
            [ordered]@{
                rank = [int]$_.rank
                featureName = [string]$_.featureName
                rawValue = [double]$_.rawValue
                direction = [string]$_.direction
                contribution = [double]$_.contribution
            }
        })
}
Write-AcceptanceEvidence -Value $report -Path $OutputPath
}
finally {
    try {
        Set-LlmTimeout -Timeout $configuredTimeout
    }
    finally {
        if ($null -eq $originalTimeout) {
            Remove-Item Env:EDUTWIN_AI_TIMEOUT -ErrorAction SilentlyContinue
        }
        else {
            $env:EDUTWIN_AI_TIMEOUT = $originalTimeout
        }
    }
}
$report
