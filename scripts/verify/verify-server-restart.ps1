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
        throw "Server restart input is missing: $path"
    }
}
$closedLoop = Get-Content -LiteralPath $ClosedLoopEvidence -Raw | ConvertFrom-Json -Depth 40
Assert-AcceptanceCondition -Condition ([string]$closedLoop.status -eq 'PASSED') -Message 'Server restart requires passed closed-loop evidence.'
$environment = Read-AcceptanceEnvironment -Path $EnvFile
$studentPassword = Get-RequiredEnvironmentValue -Environment $environment -Name 'EDUTWIN_DEMO_STUDENT_PASSWORD'
$sshOptions = @(
    '-o', 'BatchMode=yes',
    '-o', 'StrictHostKeyChecking=yes',
    '-o', 'ConnectTimeout=15',
    '-o', 'ServerAliveInterval=15',
    '-o', 'ServerAliveCountMax=20',
    '-i', $IdentityFile
)
$remote = "${SshUser}@${Server}"

function Invoke-RemoteSql {
    param([Parameter(Mandatory)][string]$Sql)

    $script = @"
set -eu
cd '$RemoteRelease'
cat <<'SQL' | docker compose --env-file infra/env/.env -f infra/compose/compose.yaml exec -T mysql sh -c 'MYSQL_PWD="`$MYSQL_PASSWORD" mysql --batch --skip-column-names -u"`$MYSQL_USER" "`$MYSQL_DATABASE"'
$Sql
SQL
"@
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($script.Replace("`r", '')))
    $lines = @(& ssh @sshOptions $remote "printf '%s' '$encoded' | base64 -d | bash" 2>$null)
    if ($LASTEXITCODE -ne 0) {
        throw 'Remote restart verification query failed.'
    }
    return $lines
}

$remoteScript = @'
set -eu
cd '__REMOTE_RELEASE__'
compose_file='infra/compose/compose.yaml'
env_file='infra/env/.env'
docker compose --env-file "$env_file" -f "$compose_file" stop --timeout 60
docker compose --env-file "$env_file" -f "$compose_file" up -d --no-build --wait --wait-timeout 600 --remove-orphans
for service in mysql redis model-service backend frontend caddy; do
  cid=$(docker compose --env-file "$env_file" -f "$compose_file" ps -q "$service")
  test -n "$cid"
  health=$(docker inspect "$cid" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}')
  oom=$(docker inspect "$cid" --format '{{.State.OOMKilled}}')
  printf 'SERVICE\t%s\t%s\t%s\t%s\n' "$service" "$cid" "$health" "$oom"
done
'@
$remoteScript = $remoteScript.Replace('__REMOTE_RELEASE__', $RemoteRelease)
$encodedScript = [Convert]::ToBase64String(
    [Text.Encoding]::UTF8.GetBytes($remoteScript.Replace("`r", ''))
)
$remoteWork = "/tmp/edutwin-restart-$([Guid]::NewGuid().ToString('N'))"
$launcherScript = @'
set -eu
work='__REMOTE_WORK__'
install -d -m 0700 "$work"
if [ ! -f "$work/launched" ]; then
  printf '%s' '__ENCODED_SCRIPT__' | base64 -d > "$work/restart.sh"
  chmod 0700 "$work/restart.sh"
  : > "$work/launched"
  nohup sh -c '"$1" > "$2" 2>&1; code=$?; printf "%s\n" "$code" > "$3"' sh \
    "$work/restart.sh" "$work/output.log" "$work/status" \
    </dev/null >/dev/null 2>&1 &
fi
'@
$launcherScript = $launcherScript.Replace('__REMOTE_WORK__', $remoteWork)
$launcherScript = $launcherScript.Replace('__ENCODED_SCRIPT__', $encodedScript)
$encodedLauncher = [Convert]::ToBase64String(
    [Text.Encoding]::UTF8.GetBytes($launcherScript.Replace("`r", ''))
)
for ($attempt = 1; $attempt -le 3; $attempt++) {
    & ssh @sshOptions $remote `
        "printf '%s' '$encodedLauncher' | base64 -d | bash" 2>$null
    if ($LASTEXITCODE -eq 0) { break }
    if ($attempt -lt 3) { Start-Sleep -Seconds 5 }
}

$output = $null
$restartStatus = $null
$deadline = [DateTimeOffset]::UtcNow.AddMinutes(15)
try {
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        $poll = @(& ssh @sshOptions $remote `
                "if [ -f '$remoteWork/status' ]; then printf '__DONE__\n'; cat '$remoteWork/status'; cat '$remoteWork/output.log'; else printf '__PENDING__\n'; fi" 2>$null)
        if ($LASTEXITCODE -eq 0 -and $poll.Count -gt 0 -and $poll[0] -eq '__DONE__') {
            $restartStatus = [int]$poll[1]
            $output = @($poll | Select-Object -Skip 2)
            break
        }
        Start-Sleep -Seconds 10
    }
}
finally {
    & ssh @sshOptions $remote "rm -rf -- '$remoteWork'" 2>$null | Out-Null
}
if ($null -eq $restartStatus) {
    throw 'Server Compose restart did not finish within 15 minutes.'
}
if ($restartStatus -ne 0) {
    throw "Server Compose restart failed with remote exit code $restartStatus."
}
$services = @()
foreach ($line in @($output)) {
    if ([string]::IsNullOrWhiteSpace([string]$line)) { continue }
    if (-not ([string]$line).StartsWith("SERVICE`t")) { continue }
    $fields = ([string]$line).Split("`t")
    if ($fields.Count -ne 5 -or $fields[0] -ne 'SERVICE') {
        throw "Unexpected server restart output: $line"
    }
    Assert-AcceptanceCondition `
        -Condition ($fields[3] -in @('healthy', 'running') -and $fields[4] -eq 'false') `
        -Message "Server service is unhealthy after restart: $($fields[1])"
    $services += [ordered]@{
        service = $fields[1]
        containerId = $fields[2]
        health = $fields[3]
        oomKilled = $false
    }
}
Assert-AcceptanceCondition -Condition ($services.Count -eq 6) -Message 'Server restart did not restore all six services.'

$student = Connect-EduTwinUser `
    -BaseUrl $BaseUrl `
    -Username ([string]$closedLoop.selection.username) `
    -Password $studentPassword
$token = [string]$student.accessToken
$courseId = [string]$closedLoop.selection.courseId
$studentId = [string]$closedLoop.selection.studentId
$jobId = [string]$closedLoop.trace.analysisJobId
$snapshotId = [string]$closedLoop.trace.snapshotId
$job = (Invoke-AcceptanceHttp `
        -BaseUrl $BaseUrl `
        -Path "/api/v1/analysis/jobs/$jobId" `
        -Token $token).Body
$twin = (Invoke-AcceptanceHttp `
        -BaseUrl $BaseUrl `
        -Path "/api/v1/courses/$courseId/students/$studentId/twin/current" `
        -Token $token).Body
$history = (Invoke-AcceptanceHttp `
        -BaseUrl $BaseUrl `
        -Path "/api/v1/courses/$courseId/students/$studentId/twin/history?limit=100" `
        -Token $token).Body
$plan = (Invoke-AcceptanceHttp `
        -BaseUrl $BaseUrl `
        -Path "/api/v1/courses/$courseId/students/$studentId/learning-plans/current" `
        -Token $token).Body
$analysisRows = @(Invoke-RemoteSql -Sql (Get-AcceptanceAnalysisSql -JobId $jobId))
if ($analysisRows.Count -ne 1) {
    throw "Remote restart analysis query returned $($analysisRows.Count) rows."
}
$analysis = ConvertFrom-AcceptanceAnalysisRow -Row ([string]$analysisRows[0])
$pointerSql = "SELECT snapshot_id FROM twin_current_pointer WHERE course_id = '$courseId' AND student_id = '$studentId';"
$pointerRows = @(Invoke-RemoteSql -Sql $pointerSql)
if ($pointerRows.Count -ne 1) {
    throw "Remote restart current pointer query returned $($pointerRows.Count) rows."
}
Assert-AcceptanceCondition -Condition ([string]$job.status -eq 'COMPLETED') -Message 'Server closed-loop job was not durable across restart.'
Assert-AcceptanceCondition -Condition ([string]$analysis.snapshotId -eq $snapshotId) -Message 'Server job snapshot identity changed across restart.'
Assert-AcceptanceCondition -Condition ([string]$pointerRows[0] -eq $snapshotId) -Message 'Server current twin identity changed across restart.'
Assert-AcceptanceCondition `
    -Condition ([long]$twin.snapshotVersion -eq [long]$closedLoop.numericChanges.snapshotVersion.after) `
    -Message 'Server twin version changed across restart.'
Assert-AcceptanceCondition `
    -Condition ([long]$history.total -eq [long]$closedLoop.numericChanges.snapshotCount.after) `
    -Message 'Server restart duplicated or lost snapshot history.'
Assert-AcceptanceCondition `
    -Condition ([long]$plan.version -eq [long]$closedLoop.numericChanges.planVersion.after) `
    -Message 'Server plan version changed across restart.'

$report = [ordered]@{
    schemaVersion = 1
    kind = 'edutwin-server-restart-verification'
    status = 'PASSED'
    server = $Server
    verifiedAt = [DateTimeOffset]::UtcNow.ToString('o')
    services = $services
    courseId = $courseId
    studentId = $studentId
    analysisJobId = $jobId
    snapshotId = $snapshotId
    snapshotVersion = [long]$twin.snapshotVersion
    snapshotCount = [long]$history.total
    planVersion = [long]$plan.version
}
Write-AcceptanceEvidence -Value $report -Path $OutputPath
$report
