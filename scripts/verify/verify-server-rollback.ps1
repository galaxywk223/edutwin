[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$Server,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$SshUser,
    [Parameter(Mandatory)][string]$IdentityFile,
    [Parameter(Mandatory)][string]$BaseUrl,
    [Parameter(Mandatory)][string]$EnvFile,
    [Parameter(Mandatory)][string]$ClosedLoopEvidence,
    [Parameter(Mandatory)][string]$OutputPath,
    [ValidatePattern('^/[A-Za-z0-9._/-]+$')]
    [string]$RemoteRelease = '/opt/edutwin/releases/current'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
. (Join-Path $repositoryRoot 'tests\support\Acceptance.Http.ps1')
$IdentityFile = [System.IO.Path]::GetFullPath($IdentityFile)
foreach ($path in @($IdentityFile, $EnvFile, $ClosedLoopEvidence)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Server rollback input is missing: $path"
    }
}
$closedLoop = Get-Content -LiteralPath $ClosedLoopEvidence -Raw | ConvertFrom-Json -Depth 40
Assert-AcceptanceCondition -Condition ([string]$closedLoop.status -eq 'PASSED') -Message 'Server rollback requires passed closed-loop evidence.'
$environment = Read-AcceptanceEnvironment -Path $EnvFile
$studentPassword = Get-RequiredEnvironmentValue -Environment $environment -Name 'EDUTWIN_DEMO_STUDENT_PASSWORD'
$sshOptions = @(
    '-o', 'BatchMode=yes',
    '-o', 'StrictHostKeyChecking=yes',
    '-i', $IdentityFile
)
$remote = "${SshUser}@${Server}"

function Invoke-RemoteScript {
    param([Parameter(Mandatory)][string]$Script)
    $encodedScript = [Convert]::ToBase64String(
        [Text.Encoding]::UTF8.GetBytes($Script.Replace("`r", ''))
    )
    $output = & ssh @sshOptions $remote `
        "printf '%s' '$encodedScript' | base64 -d | bash" 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Remote rollback command failed: $($output -join ' ')"
    }
    return @($output)
}

function Get-RemoteDeploymentState {
    $script = @'
set -eu
cd '__REMOTE_RELEASE__'
compose_file='infra/compose/compose.yaml'
env_file='infra/env/.env'
cat <<'SQL' | docker compose --env-file "$env_file" -f "$compose_file" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql --batch --skip-column-names -u"$MYSQL_USER" "$MYSQL_DATABASE"'
SELECT task_name, active_version_id, rollback_version_id
FROM model_deployment
WHERE task_name IN ('NEXT_CORRECT','RISK','EXPLANATION')
ORDER BY FIELD(task_name,'NEXT_CORRECT','RISK','EXPLANATION');
SQL
'@
    $lines = Invoke-RemoteScript -Script $script.Replace('__REMOTE_RELEASE__', $RemoteRelease)
    $result = [ordered]@{}
    foreach ($line in $lines) {
        if ([string]::IsNullOrWhiteSpace([string]$line)) { continue }
        $fields = ([string]$line).Split("`t")
        if ($fields.Count -ne 3) { throw "Unexpected remote deployment row: $line" }
        $result[$fields[0]] = [ordered]@{ active = $fields[1]; rollback = $fields[2] }
    }
    if ($result.Count -ne 3) { throw 'Remote rollback deployment state is incomplete.' }
    foreach ($entry in $result.Values) {
        if ([string]::IsNullOrWhiteSpace([string]$entry.rollback) -or $entry.active -eq $entry.rollback) {
            throw 'Remote rollback deployment must reference a distinct frozen model.'
        }
    }
    return $result
}

function Get-RemoteAnalysisRecord {
    param([Parameter(Mandatory)][string]$JobId)

    $sql = Get-AcceptanceAnalysisSql -JobId $JobId
    $script = @"
set -eu
cd '$RemoteRelease'
cat <<'SQL' | docker compose --env-file infra/env/.env -f infra/compose/compose.yaml exec -T mysql sh -c 'MYSQL_PWD="`$MYSQL_PASSWORD" mysql --batch --skip-column-names -u"`$MYSQL_USER" "`$MYSQL_DATABASE"'
$sql
SQL
"@
    $rows = @(Invoke-RemoteScript -Script $script)
    if ($rows.Count -ne 1) {
        throw "Remote analysis query returned $($rows.Count) rows for $JobId."
    }
    return ConvertFrom-AcceptanceAnalysisRow -Row ([string]$rows[0])
}

function Switch-RemoteDeploymentPointers {
    $script = @'
set -eu
cd '__REMOTE_RELEASE__'
compose_file='infra/compose/compose.yaml'
env_file='infra/env/.env'
cat <<'SQL' | docker compose --env-file "$env_file" -f "$compose_file" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" "$MYSQL_DATABASE"'
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
SQL
'@
    Invoke-RemoteScript -Script $script.Replace('__REMOTE_RELEASE__', $RemoteRelease) | Out-Null
}

function Set-RemoteModelMode {
    param([ValidateSet('active', 'rollback')][string]$Mode)
    $script = @'
set -eu
cd '__REMOTE_RELEASE__'
compose_file='infra/compose/compose.yaml'
env_file='infra/env/.env'
EDUTWIN_MODEL_DEPLOYMENT_MODE='__MODE__' docker compose --env-file "$env_file" -f "$compose_file" up -d --no-deps --force-recreate --wait --wait-timeout 180 model-service
cid=$(docker compose --env-file "$env_file" -f "$compose_file" ps -q model-service)
test -n "$cid"
test "$(docker inspect "$cid" --format '{{.State.Health.Status}}')" = 'healthy'
'@
    $script = $script.Replace('__REMOTE_RELEASE__', $RemoteRelease).Replace('__MODE__', $Mode)
    Invoke-RemoteScript -Script $script | Out-Null
}

$courseId = [string]$closedLoop.selection.courseId
$studentId = [string]$closedLoop.selection.studentId
$student = Connect-EduTwinUser `
    -BaseUrl $BaseUrl `
    -Username ([string]$closedLoop.selection.username) `
    -Password $studentPassword
$studentToken = [string]$student.accessToken

function Submit-And-VerifyRemoteModels {
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
        -Headers @{ 'Idempotency-Key' = "acceptance-server-$Scenario-$([Guid]::NewGuid().ToString('N'))" } `
        -Body ([ordered]@{
            questionId = [string]$question.questionId
            selectedChoiceId = $correct
            occurredAt = [DateTimeOffset]::UtcNow.AddMilliseconds(-100).ToString('o')
        }) `
        -ExpectedStatus @(202)
    $jobId = [string]$submission.Body.jobId
    $sse = Wait-EduTwinSseTerminal -BaseUrl $BaseUrl -Token $studentToken -JobId $jobId -TimeoutSeconds 15
    Assert-AcceptanceCondition -Condition ([string]$sse.TerminalJob.status -eq 'COMPLETED') -Message "$Scenario server model task did not complete."
    $analysis = Get-RemoteAnalysisRecord -JobId $jobId
    $modelReasons = @('KNOWLEDGE_MODEL_UNAVAILABLE', 'RISK_MODEL_UNAVAILABLE', 'MODEL_TIMEOUT', 'MODEL_CONTRACT_ERROR')
    Assert-AcceptanceCondition `
        -Condition (@($analysis.degradationReasons | Where-Object { $_ -in $modelReasons }).Count -eq 0) `
        -Message "$Scenario server model task used a rule or contract fallback."
    $effective = @{}
    foreach ($model in $analysis.effectiveModelVersions) {
        $effective[[string]$model.purpose] = [string]$model.modelVersion
    }
    foreach ($purpose in @('RISK', 'EXPLANATION')) {
        Assert-AcceptanceCondition `
            -Condition ([string]$effective[$purpose] -eq [string]$Expected[$purpose].active) `
            -Message "$Scenario server effective $purpose version differs from the deployment pointer."
    }
    return [ordered]@{
        analysisJobId = $jobId
        snapshotId = [string]$analysis.snapshotId
        effectiveVersions = $effective
        degradationReasons = @($analysis.degradationReasons)
    }
}

$initial = Get-RemoteDeploymentState
$switched = $false
$rollbackResult = $null
$restoredResult = $null
try {
    Switch-RemoteDeploymentPointers
    $switched = $true
    $rollbackState = Get-RemoteDeploymentState
    foreach ($purpose in @('NEXT_CORRECT', 'RISK', 'EXPLANATION')) {
        Assert-AcceptanceCondition `
            -Condition ([string]$rollbackState[$purpose].active -eq [string]$initial[$purpose].rollback) `
            -Message "Remote rollback did not activate $purpose."
    }
    Set-RemoteModelMode -Mode rollback
    $rollbackResult = Submit-And-VerifyRemoteModels -Scenario 'model-rollback' -Expected $rollbackState

    Switch-RemoteDeploymentPointers
    $switched = $false
    $restoredState = Get-RemoteDeploymentState
    foreach ($purpose in @('NEXT_CORRECT', 'RISK', 'EXPLANATION')) {
        Assert-AcceptanceCondition `
            -Condition ([string]$restoredState[$purpose].active -eq [string]$initial[$purpose].active) `
            -Message "Remote winner restoration did not reactivate $purpose."
    }
    Set-RemoteModelMode -Mode active
    $restoredResult = Submit-And-VerifyRemoteModels -Scenario 'model-restored' -Expected $restoredState
}
finally {
    if ($switched) {
        Switch-RemoteDeploymentPointers
    }
    Set-RemoteModelMode -Mode active
}

$report = [ordered]@{
    schemaVersion = 1
    kind = 'edutwin-server-model-rollback-verification'
    status = 'PASSED'
    server = $Server
    verifiedAt = [DateTimeOffset]::UtcNow.ToString('o')
    courseId = $courseId
    studentId = $studentId
    originalDeployments = $initial
    rollback = $rollbackResult
    restoredWinner = $restoredResult
}
Write-AcceptanceEvidence -Value $report -Path $OutputPath
$report
