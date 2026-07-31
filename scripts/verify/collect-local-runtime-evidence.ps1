[CmdletBinding()]
param(
    [string]$EnvFile,
    [Parameter(Mandatory)][string]$OutputPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$composeFile = Join-Path $repositoryRoot 'infra\compose\compose.yaml'
if ([string]::IsNullOrWhiteSpace($EnvFile)) {
    $EnvFile = Join-Path $repositoryRoot 'infra\env\.env'
}
$EnvFile = [System.IO.Path]::GetFullPath($EnvFile)
$OutputPath = [System.IO.Path]::GetFullPath($OutputPath)

foreach ($path in @($composeFile, $EnvFile)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required runtime evidence file is missing: $path"
    }
}

function Invoke-Captured {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [Parameter(Mandatory)][string[]]$Arguments
    )

    $output = & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed with exit code ${LASTEXITCODE}: $FilePath"
    }
    return @($output)
}

$compose = @('compose', '--env-file', $EnvFile, '-f', $composeFile)
$services = @('mysql', 'redis', 'model-service', 'backend', 'frontend', 'caddy')
$images = @()
$containerStates = @()
foreach ($service in $services) {
    $containerId = [string](Invoke-Captured docker (@($compose) + @('ps', '-q', $service)) | Select-Object -First 1)
    if ([string]::IsNullOrWhiteSpace($containerId)) {
        throw "Compose service has no container: $service"
    }
    $containerJson = [string](Invoke-Captured docker @('inspect', $containerId) | Out-String)
    $container = @($containerJson | ConvertFrom-Json)[0]
    $imageId = [string]$container.Image
    $platform = [string](Invoke-Captured docker @(
            'image', 'inspect', $imageId, '--format', '{{.Os}}/{{.Architecture}}'
        ) | Select-Object -First 1)
    if ($platform -ne 'linux/amd64' -or $imageId -notmatch '^sha256:[0-9a-f]{64}$') {
        throw "Service $service is not running a verified linux/amd64 image."
    }
    $health = if ($null -ne $container.State.Health) {
        [string]$container.State.Health.Status
    }
    else {
        [string]$container.State.Status
    }
    if ($health -notin @('healthy', 'running') -or
        [bool]$container.State.OOMKilled -or
        [int]$container.RestartCount -ne 0) {
        throw "Service $service is unhealthy, OOM-killed, or restarted."
    }
    $images += [ordered]@{
        name = [string]$container.Config.Image
        service = $service
        id = $imageId
        platform = $platform
    }
    $containerStates += [ordered]@{
        service = $service
        containerId = $containerId
        health = $health
        oomKilled = [bool]$container.State.OOMKilled
        restartCount = [int]$container.RestartCount
    }
}

$query = @'
SELECT version, description, checksum,
       DATE_FORMAT(installed_on, '%Y-%m-%dT%H:%i:%sZ'), success
FROM flyway_schema_history
WHERE type = 'SQL'
ORDER BY installed_rank;
'@
$shell = 'mysql --batch --skip-column-names -u"$MYSQL_USER" --password="$MYSQL_PASSWORD" "$MYSQL_DATABASE" -e ' +
    "`"$($query.Replace("`r", ' ').Replace("`n", ' '))`""
$migrationLines = Invoke-Captured docker (@($compose) + @('exec', '-T', 'mysql', 'sh', '-c', $shell))
$migrations = @()
foreach ($line in $migrationLines) {
    if ([string]::IsNullOrWhiteSpace([string]$line)) {
        continue
    }
    $fields = ([string]$line).Split("`t")
    if ($fields.Count -ne 5) {
        throw "Unexpected Flyway evidence row: $line"
    }
    $migrations += [ordered]@{
        version = $fields[0]
        description = $fields[1]
        checksum = [int]$fields[2]
        installedOn = $fields[3]
        success = $fields[4] -eq '1'
    }
}
if ($migrations.Count -eq 0 -or @($migrations | Where-Object { -not $_.success }).Count -gt 0) {
    throw 'Flyway runtime evidence is empty or contains a failed migration.'
}

$report = [ordered]@{
    schemaVersion = 1
    kind = 'edutwin-local-runtime-evidence'
    capturedAt = [DateTimeOffset]::UtcNow.ToString('o')
    images = $images
    migrations = $migrations
    containers = $containerStates
}
New-Item -Path (Split-Path -Parent $OutputPath) -ItemType Directory -Force | Out-Null
$report | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $OutputPath -Encoding utf8NoBOM
$report
