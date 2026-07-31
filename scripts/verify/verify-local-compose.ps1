[CmdletBinding()]
param(
    [string]$EnvFile
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$composeFile = Join-Path $repositoryRoot 'infra\compose\compose.yaml'
if ([string]::IsNullOrWhiteSpace($EnvFile)) {
    $EnvFile = Join-Path $repositoryRoot 'infra\env\.env'
}
$EnvFile = [System.IO.Path]::GetFullPath($EnvFile)
foreach ($path in @($composeFile, $EnvFile)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required local Compose file is missing: $path"
    }
}

function Invoke-Checked {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [Parameter(Mandatory)][string[]]$Arguments
    )

    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed with exit code ${LASTEXITCODE}: $FilePath"
    }
}

$requiredEnv = @(
    'MYSQL_PASSWORD',
    'MYSQL_ROOT_PASSWORD',
    'REDIS_PASSWORD',
    'EDUTWIN_JWT_SECRET',
    'EDUTWIN_ADMIN_USERNAME',
    'EDUTWIN_ADMIN_PASSWORD',
    'EDUTWIN_DEMO_TEACHER_PASSWORD',
    'EDUTWIN_DEMO_STUDENT_PASSWORD'
)
$environment = @{}
foreach ($line in Get-Content -LiteralPath $EnvFile) {
    if ($line -match '^([^#=]+)=(.*)$') {
        $environment[$matches[1]] = $matches[2]
    }
}
foreach ($name in $requiredEnv) {
    $value = [string]$environment[$name]
    if ([string]::IsNullOrWhiteSpace($value) -or $value -match 'CHANGE_ME') {
        throw "Required local environment value is missing or unchanged: $name"
    }
}
if ([string]$environment['EDUTWIN_DEMO_IMPORT_ENABLED'] -ne 'true') {
    throw 'EDUTWIN_DEMO_IMPORT_ENABLED must be true for empty-database acceptance.'
}

$compose = @('compose', '--env-file', $EnvFile, '-f', $composeFile)
$previousPlatform = $env:DOCKER_DEFAULT_PLATFORM
$env:DOCKER_DEFAULT_PLATFORM = 'linux/amd64'
Push-Location $repositoryRoot
try {
    & docker @compose down --volumes --remove-orphans
    if ($LASTEXITCODE -ne 0) {
        throw 'The previous local Compose project could not be removed.'
    }
    Invoke-Checked docker (@($compose) + @('build', '--pull', 'backend', 'model-service', 'frontend'))
    Invoke-Checked docker (@($compose) + @('pull', 'mysql', 'redis', 'caddy'))
    Invoke-Checked docker (@($compose) + @(
            'up', '-d', '--no-build', '--wait', '--wait-timeout', '5400', '--remove-orphans'
        ))

    $services = @('mysql', 'redis', 'model-service', 'backend', 'frontend', 'caddy')
    foreach ($service in $services) {
        $containerId = [string](& docker @compose ps -q $service | Select-Object -First 1)
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($containerId)) {
            throw "Compose service has no running container: $service"
        }
        $container = @((& docker inspect $containerId | ConvertFrom-Json))[0]
        $health = if ($null -ne $container.State.Health) {
            [string]$container.State.Health.Status
        }
        else {
            [string]$container.State.Status
        }
        if ($health -notin @('healthy', 'running') -or
            [bool]$container.State.OOMKilled -or
            [int]$container.RestartCount -ne 0) {
            throw "Compose service failed health or stability checks: $service"
        }
        if ([long]$container.HostConfig.Memory -le 0 -or [long]$container.HostConfig.NanoCpus -le 0) {
            throw "Compose service lacks memory or CPU limits: $service"
        }
        $publishedBindings = @(
            $container.NetworkSettings.Ports.PSObject.Properties |
                Where-Object { $null -ne $_.Value } |
                ForEach-Object { $_.Value }
        )
        if ($service -eq 'caddy') {
            $unexpectedBindings = @(
                $publishedBindings |
                    Where-Object { [string]$_.HostPort -ne '80' }
            )
            if ($publishedBindings.Count -eq 0 -or $unexpectedBindings.Count -ne 0) {
                throw 'Caddy must be the only service binding host TCP 80.'
            }
        }
        elseif ($publishedBindings.Count -ne 0) {
            throw "Non-edge service publishes a host port: $service"
        }
    }

    $projectName = if ($environment.ContainsKey('COMPOSE_PROJECT_NAME')) {
        [string]$environment['COMPOSE_PROJECT_NAME']
    }
    else {
        'edutwin'
    }
    $dataNetwork = @(& docker network inspect "$projectName-data" | ConvertFrom-Json)[0]
    if (-not [bool]$dataNetwork.Internal) {
        throw 'The Compose data network is not internal.'
    }

    Invoke-Checked docker (@($compose) + @(
            'exec', '-T', 'model-service', 'sh', '-c',
            'test ! -e /app/data/raw && test ! -e /app/data/interim && test ! -e /app/data/processed && test ! -e /app/demo/database/source_lineage.csv'
        ))
    Invoke-Checked docker (@($compose) + @(
            'exec', '-T', 'backend', 'sh', '-c',
            'test ! -e /app/data/raw && test ! -e /app/data/interim && test ! -e /app/data/processed'
        ))

    $healthResponse = Invoke-WebRequest `
        -Uri 'http://127.0.0.1/healthz' `
        -UseBasicParsing `
        -TimeoutSec 10
    if ($healthResponse.StatusCode -ne 200) {
        throw "Caddy health endpoint returned $($healthResponse.StatusCode)."
    }
}
finally {
    Pop-Location
    if ($null -eq $previousPlatform) {
        Remove-Item Env:DOCKER_DEFAULT_PLATFORM -ErrorAction SilentlyContinue
    }
    else {
        $env:DOCKER_DEFAULT_PLATFORM = $previousPlatform
    }
}

[pscustomobject]@{
    status = 'PASSED'
    emptyDatabaseStarted = $true
    platform = 'linux/amd64'
    publicPort = 80
    serviceCount = 6
}
