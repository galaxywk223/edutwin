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

function Invoke-MySql {
    param([Parameter(Mandatory)][string]$Sql)
    $command = 'mysql --batch --skip-column-names -u"$MYSQL_USER" --password="$MYSQL_PASSWORD" "$MYSQL_DATABASE" -e "' + $Sql + '"'
    $output = & docker @compose exec -T mysql sh -c $command 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Model deployment SQL failed: $($output -join ' ')"
    }
    return @($output | Where-Object { [string]$_ -notmatch '^mysql: \[Warning\]' })
}

function Wait-ModelService {
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(120)
    do {
        $containerId = [string](& docker @compose ps -q model-service | Select-Object -First 1)
        if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($containerId)) {
            $container = @(& docker inspect $containerId | ConvertFrom-Json)[0]
            if ([string]$container.State.Health.Status -eq 'healthy') { return }
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw 'Model service did not become healthy after deployment mode switch.'
}

function Get-DeploymentState {
    $lines = Invoke-MySql -Sql "SELECT task_name, active_version_id, rollback_version_id FROM model_deployment WHERE task_name IN ('NEXT_CORRECT','RISK','EXPLANATION') ORDER BY FIELD(task_name,'NEXT_CORRECT','RISK','EXPLANATION');"
    $result = [ordered]@{}
    foreach ($line in $lines) {
        $fields = ([string]$line).Split("`t")
        if ($fields.Count -ne 3) { throw "Unexpected model deployment row: $line" }
        $result[$fields[0]] = [ordered]@{ active = $fields[1]; rollback = $fields[2] }
    }
    if ($result.Count -ne 3) { throw 'Rollback deployment state is incomplete.' }
    foreach ($entry in $result.Values) {
        if ([string]::IsNullOrWhiteSpace([string]$entry.rollback) -or $entry.active -eq $entry.rollback) {
            throw 'Rollback deployment must reference a distinct frozen model.'
        }
    }
    return $result
}

function Switch-DeploymentPointers {
    $sql = @"
START TRANSACTION;
CREATE TEMPORARY TABLE deployment_swap AS
SELECT task_name, active_version_id AS old_active, rollback_version_id AS old_rollback
FROM model_deployment
WHERE task_name IN ('NEXT_CORRECT','RISK','EXPLANATION');
UPDATE model_version mv JOIN deployment_swap ds ON mv.version_id = ds.old_active
SET mv.status = 'FROZEN';
UPDATE model_version mv JOIN deployment_swap ds ON mv.version_id = ds.old_rollback
SET mv.status = 'ACTIVE';
UPDATE model_deployment md JOIN deployment_swap ds ON md.task_name = ds.task_name
SET md.active_version_id = ds.old_rollback,
    md.rollback_version_id = ds.old_active,
    md.deployed_at = UTC_TIMESTAMP(6),
    md.deployed_by = 'acceptance-model-rollback';
DROP TEMPORARY TABLE deployment_swap;
COMMIT;
"@
    Invoke-MySql -Sql ($sql.Replace("`r", ' ').Replace("`n", ' ')) | Out-Null
}

function Set-ModelMode {
    param([ValidateSet('active', 'rollback')][string]$Mode)
    $env:EDUTWIN_MODEL_DEPLOYMENT_MODE = $Mode
    Invoke-ComposeChecked -Arguments @('up', '-d', '--no-deps', '--force-recreate', 'model-service') | Out-Null
    Wait-ModelService
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

function Submit-And-VerifyModels {
    param(
        [Parameter(Mandatory)][string]$Scenario,
        [Parameter(Mandatory)][System.Collections.IDictionary]$Expected
    )
    $question = (Invoke-AcceptanceHttp `
            -BaseUrl $BaseUrl `
            -Path "/api/v1/courses/$courseId/practice/next" `
            -Token $studentToken).Body
    $correct = Get-DemoCorrectAnswer -RepositoryRoot $repositoryRoot -QuestionId ([string]$question.questionId)
    $submission = Invoke-AcceptanceHttp `
        -BaseUrl $BaseUrl `
        -Path "/api/v1/courses/$courseId/answers" `
        -Method POST `
        -Token $studentToken `
        -Headers @{ 'Idempotency-Key' = "acceptance-$Scenario-$([Guid]::NewGuid().ToString('N'))" } `
        -Body ([ordered]@{
            questionId = [string]$question.questionId
            selectedChoiceId = $correct
            occurredAt = [DateTimeOffset]::UtcNow.AddMilliseconds(-100).ToString('o')
        }) `
        -ExpectedStatus @(202)
    $jobId = [string]$submission.Body.jobId
    $sse = Wait-EduTwinSseTerminal -BaseUrl $BaseUrl -Token $studentToken -JobId $jobId -TimeoutSeconds 15
    Assert-AcceptanceCondition -Condition ([string]$sse.TerminalJob.status -eq 'COMPLETED') -Message "$Scenario model task did not complete."
    $analysis = Get-LocalAcceptanceAnalysisRecord -ComposeArguments $compose -JobId $jobId
    $modelReasons = @('KNOWLEDGE_MODEL_UNAVAILABLE', 'RISK_MODEL_UNAVAILABLE', 'MODEL_TIMEOUT', 'MODEL_CONTRACT_ERROR')
    Assert-AcceptanceCondition `
        -Condition (@($analysis.degradationReasons | Where-Object { $_ -in $modelReasons }).Count -eq 0) `
        -Message "$Scenario model task used a rule or contract fallback."
    $effective = @{}
    foreach ($model in $analysis.effectiveModelVersions) {
        $effective[[string]$model.purpose] = [string]$model.modelVersion
    }
    foreach ($purpose in @('RISK', 'EXPLANATION')) {
        Assert-AcceptanceCondition `
            -Condition ([string]$effective[$purpose] -eq [string]$Expected[$purpose].active) `
            -Message "$Scenario effective $purpose version differs from the deployment pointer."
    }
    return [ordered]@{
        analysisJobId = $jobId
        snapshotId = [string]$analysis.snapshotId
        effectiveVersions = $effective
        degradationReasons = @($analysis.degradationReasons)
    }
}

$originalEnvironmentMode = $env:EDUTWIN_MODEL_DEPLOYMENT_MODE
$initial = Get-DeploymentState
$switched = $false
$rollbackResult = $null
$restoredResult = $null
try {
    Switch-DeploymentPointers
    $switched = $true
    $rollbackState = Get-DeploymentState
    foreach ($purpose in @('NEXT_CORRECT', 'RISK', 'EXPLANATION')) {
        Assert-AcceptanceCondition `
            -Condition ([string]$rollbackState[$purpose].active -eq [string]$initial[$purpose].rollback) `
            -Message "Rollback pointer did not activate the recorded $purpose rollback model."
    }
    Set-ModelMode -Mode rollback
    $rollbackResult = Submit-And-VerifyModels -Scenario 'model-rollback' -Expected $rollbackState

    Switch-DeploymentPointers
    $switched = $false
    $restoredState = Get-DeploymentState
    foreach ($purpose in @('NEXT_CORRECT', 'RISK', 'EXPLANATION')) {
        Assert-AcceptanceCondition `
            -Condition ([string]$restoredState[$purpose].active -eq [string]$initial[$purpose].active) `
            -Message "Winner restoration did not reactivate $purpose."
    }
    Set-ModelMode -Mode active
    $restoredResult = Submit-And-VerifyModels -Scenario 'model-restored' -Expected $restoredState
}
finally {
    if ($switched) {
        Switch-DeploymentPointers
    }
    try {
        Set-ModelMode -Mode active
    }
    finally {
        if ($null -eq $originalEnvironmentMode) {
            Remove-Item Env:EDUTWIN_MODEL_DEPLOYMENT_MODE -ErrorAction SilentlyContinue
        }
        else {
            $env:EDUTWIN_MODEL_DEPLOYMENT_MODE = $originalEnvironmentMode
        }
    }
}

$report = [ordered]@{
    schemaVersion = 1
    kind = 'edutwin-model-rollback-verification'
    status = 'PASSED'
    baseUrl = $BaseUrl
    verifiedAt = [DateTimeOffset]::UtcNow.ToString('o')
    courseId = $courseId
    studentId = $studentId
    originalDeployments = $initial
    rollback = $rollbackResult
    restoredWinner = $restoredResult
}
Write-AcceptanceEvidence -Value $report -Path $OutputPath
$report
