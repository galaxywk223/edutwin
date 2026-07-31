[CmdletBinding()]
param(
    [string]$EnvFile,
    [string]$OutputDirectory,
    [int]$RetentionDays = 14
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$composeFile = Join-Path $repositoryRoot 'infra/compose/compose.yaml'
if ([string]::IsNullOrWhiteSpace($EnvFile)) {
    $EnvFile = Join-Path $repositoryRoot 'infra/env/.env'
}
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $repositoryRoot 'infra/backups'
}
$EnvFile = [System.IO.Path]::GetFullPath($EnvFile)
$OutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)

if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf)) {
    throw "Environment file not found: $EnvFile"
}
if ($RetentionDays -lt 1) {
    throw 'RetentionDays must be at least 1.'
}

New-Item -Path $OutputDirectory -ItemType Directory -Force | Out-Null
$timestamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
$databaseBackup = Join-Path $OutputDirectory "edutwin-mysql-$timestamp.sql.gz"
$metadataPath = Join-Path $OutputDirectory "edutwin-backup-$timestamp.json"
$temporaryBackup = "$databaseBackup.partial"

$compose = @('compose', '--env-file', $EnvFile, '-f', $composeFile)
& docker @compose ps --status running mysql | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'The MySQL service is not running.'
}

try {
    $containerBackup = "/tmp/edutwin-mysql-$timestamp.sql.gz"
    & docker @compose exec -T mysql sh -ec @'
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump \
  --host=127.0.0.1 \
  --user=root \
  --single-transaction \
  --quick \
  --routines \
  --triggers \
  --events \
  --add-drop-database \
  --hex-blob \
  --set-gtid-purged=OFF \
  --databases "$MYSQL_DATABASE" | gzip -9 > "$1"
'@ sh $containerBackup
    if ($LASTEXITCODE -ne 0) {
        throw 'mysqldump failed.'
    }

    $mysqlContainerId = (& docker @compose ps -q mysql).Trim()
    if ([string]::IsNullOrWhiteSpace($mysqlContainerId)) {
        throw 'The MySQL container ID could not be resolved.'
    }
    & docker cp "${mysqlContainerId}:$containerBackup" $temporaryBackup
    if ($LASTEXITCODE -ne 0) {
        throw 'The database backup could not be copied from the MySQL container.'
    }
    & docker @compose exec -T mysql rm -f $containerBackup
    if ($LASTEXITCODE -ne 0) {
        throw 'The temporary database backup could not be removed from the MySQL container.'
    }

    Move-Item -LiteralPath $temporaryBackup -Destination $databaseBackup -Force
    $databaseHash = (Get-FileHash -LiteralPath $databaseBackup -Algorithm SHA256).Hash.ToLowerInvariant()
    $imageLines = @(& docker @compose images --format json)
    if ($LASTEXITCODE -ne 0) {
        throw 'Compose image metadata could not be read.'
    }

    $resolvedCompose = (& docker @compose config --format json) | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) {
        throw 'Resolved Compose metadata could not be read.'
    }
    $metadata = [ordered]@{
        schema_version = 1
        created_at_utc = [DateTime]::UtcNow.ToString('o')
        database = [ordered]@{
            file = [System.IO.Path]::GetFileName($databaseBackup)
            sha256 = $databaseHash
            bytes = (Get-Item -LiteralPath $databaseBackup).Length
        }
        compose_project = $resolvedCompose.name
        images = @($imageLines | ForEach-Object { $_ | ConvertFrom-Json })
    }
    $metadata | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $metadataPath -Encoding utf8NoBOM

    $cutoff = [DateTime]::UtcNow.AddDays(-$RetentionDays)
    Get-ChildItem -LiteralPath $OutputDirectory -File |
        Where-Object {
            $_.LastWriteTimeUtc -lt $cutoff -and
            ($_.Name -like 'edutwin-mysql-*.sql.gz' -or $_.Name -like 'edutwin-backup-*.json')
        } |
        Remove-Item -Force

    Write-Output $metadataPath
}
finally {
    if (Test-Path -LiteralPath $temporaryBackup) {
        Remove-Item -LiteralPath $temporaryBackup -Force
    }
}
