[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory)]
    [string]$BackupFile,
    [string]$EnvFile,
    [string]$ExpectedSha256,
    [switch]$SkipSafetyBackup
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$composeFile = Join-Path $repositoryRoot 'infra/compose/compose.yaml'
if ([string]::IsNullOrWhiteSpace($EnvFile)) {
    $EnvFile = Join-Path $repositoryRoot 'infra/env/.env'
}
$EnvFile = [System.IO.Path]::GetFullPath($EnvFile)
$BackupFile = [System.IO.Path]::GetFullPath($BackupFile)

if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf)) {
    throw "Environment file not found: $EnvFile"
}
if (-not (Test-Path -LiteralPath $BackupFile -PathType Leaf)) {
    throw "Backup file not found: $BackupFile"
}
if (-not $BackupFile.EndsWith('.sql.gz', [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'BackupFile must use the .sql.gz format produced by backup.ps1.'
}

$actualHash = (Get-FileHash -LiteralPath $BackupFile -Algorithm SHA256).Hash.ToLowerInvariant()
if (-not [string]::IsNullOrWhiteSpace($ExpectedSha256) -and
    $actualHash -ne $ExpectedSha256.ToLowerInvariant()) {
    throw "Backup SHA-256 mismatch: expected $ExpectedSha256, got $actualHash."
}

if (-not $PSCmdlet.ShouldProcess('EduTwin MySQL database', "Restore $BackupFile")) {
    return
}

$compose = @('compose', '--env-file', $EnvFile, '-f', $composeFile)
if (-not $SkipSafetyBackup) {
    & (Join-Path $PSScriptRoot 'backup.ps1') -EnvFile $EnvFile | Out-Host
}

& docker @compose stop backend
if ($LASTEXITCODE -ne 0) {
    throw 'The backend service could not be stopped.'
}

$containerBackup = '/tmp/edutwin-restore.sql.gz'
try {
    $mysqlContainerId = (& docker @compose ps -q mysql).Trim()
    if ([string]::IsNullOrWhiteSpace($mysqlContainerId)) {
        throw 'The MySQL container is not running.'
    }
    & docker cp $BackupFile "${mysqlContainerId}:$containerBackup"
    if ($LASTEXITCODE -ne 0) {
        throw 'The backup could not be copied into the MySQL container.'
    }

    & docker @compose exec -T mysql sh -ec @'
gzip -cd "$1" | MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --host=127.0.0.1 --user=root
'@ sh $containerBackup
    if ($LASTEXITCODE -ne 0) {
        throw 'The MySQL restore failed.'
    }

    & docker @compose exec -T redis sh -ec 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --no-auth-warning FLUSHALL ASYNC'
    if ($LASTEXITCODE -ne 0) {
        throw 'Redis could not be invalidated after the database restore.'
    }
}
finally {
    & docker @compose exec -T mysql rm -f $containerBackup 2>$null
}

& docker @compose up -d --no-deps backend
if ($LASTEXITCODE -ne 0) {
    throw 'The backend service could not be started after restore.'
}
Write-Output "Restored $BackupFile (sha256=$actualHash)."
