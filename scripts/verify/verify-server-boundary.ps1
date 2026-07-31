[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$Server,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$SshUser,
    [Parameter(Mandatory)][string]$IdentityFile,
    [Parameter(Mandatory)][string]$OutputPath,
    [ValidatePattern('^/[A-Za-z0-9._/-]+$')]
    [string]$RemoteRelease = '/opt/edutwin/releases/current'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
. (Join-Path $repositoryRoot 'tests\support\Acceptance.Http.ps1')
$IdentityFile = [System.IO.Path]::GetFullPath($IdentityFile)
if (-not (Test-Path -LiteralPath $IdentityFile -PathType Leaf)) {
    throw "SSH identity file is missing: $IdentityFile"
}
$sshOptions = @(
    '-o', 'BatchMode=yes',
    '-o', 'StrictHostKeyChecking=yes',
    '-i', $IdentityFile
)
$remote = "${SshUser}@${Server}"
$remoteScript = @'
set -eu
release='__REMOTE_RELEASE__'
current=$(readlink -f "$release")
case "$current" in
  /opt/edutwin/releases/*) ;;
  *) echo 'current release is outside /opt/edutwin/releases' >&2; exit 11 ;;
esac
cd "$current"
compose_file='infra/compose/compose.yaml'
env_file='infra/env/.env'
test -f "$compose_file"
test -f "$env_file"
printf 'CURRENT\t%s\n' "$current"
printf 'ENV_MODE\t%s\n' "$(stat -c '%a' "$env_file")"
swap_bytes=$(awk 'NR > 1 { total += $3 * 1024 } END { printf "%.0f", total + 0 }' /proc/swaps)
printf 'SWAP\t%s\n' "$swap_bytes"
project=$(sed -n 's/^COMPOSE_PROJECT_NAME=//p' "$env_file" | tail -n 1)
test -n "$project"
internal=$(docker network inspect "${project}-data" --format '{{.Internal}}')
printf 'DATA_NETWORK\t%s\n' "$internal"
for service in mysql redis model-service backend frontend caddy; do
  cid=$(docker compose --env-file "$env_file" -f "$compose_file" ps -q "$service")
  test -n "$cid"
  image_id=$(docker inspect "$cid" --format '{{.Image}}')
  platform=$(docker image inspect "$image_id" --format '{{.Os}}/{{.Architecture}}')
  health=$(docker inspect "$cid" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}')
  oom=$(docker inspect "$cid" --format '{{.State.OOMKilled}}')
  restart=$(docker inspect "$cid" --format '{{.RestartCount}}')
  memory=$(docker inspect "$cid" --format '{{.HostConfig.Memory}}')
  nano=$(docker inspect "$cid" --format '{{.HostConfig.NanoCpus}}')
  ports=$(docker inspect "$cid" --format '{{json .NetworkSettings.Ports}}')
  printf 'SERVICE\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$service" "$image_id" "$platform" "$health" "$oom" "$restart" "$memory" "$nano" "$ports"
done
server_training_dirs=$(find -L "$current" -type d \( -name raw -o -name interim -o -name processed \) | wc -l)
printf 'SERVER_TRAINING_DIRS\t%s\n' "$server_training_dirs"
for service in backend model-service; do
  if docker compose --env-file "$env_file" -f "$compose_file" exec -T "$service" sh -c \
      'test ! -e /app/data/raw && test ! -e /app/data/interim && test ! -e /app/data/processed' </dev/null; then
    printf 'CONTAINER_TRAINING_ABSENT\t%s\ttrue\n' "$service"
  else
    printf 'CONTAINER_TRAINING_ABSENT\t%s\tfalse\n' "$service"
  fi
done
loopback_health=$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
  --header 'Host: localhost' http://127.0.0.1/healthz)
printf 'LOOPBACK_HEALTH\t%s\n' "$loopback_health"
'@
$remoteScript = $remoteScript.Replace('__REMOTE_RELEASE__', $RemoteRelease)
$encodedScript = [Convert]::ToBase64String(
    [Text.Encoding]::UTF8.GetBytes($remoteScript.Replace("`r", ''))
)
$output = & ssh @sshOptions $remote "printf '%s' '$encodedScript' | base64 -d | bash"
if ($LASTEXITCODE -ne 0) {
    throw "Server boundary inspection failed with exit code $LASTEXITCODE."
}

$currentRelease = $null
$envMode = $null
$swapBytes = 0L
$dataNetworkInternal = $false
$serverTrainingDirectories = -1
$loopbackHealth = 0
$services = @()
$containerTraining = @{}
foreach ($line in @($output)) {
    if ([string]::IsNullOrWhiteSpace([string]$line)) { continue }
    $fields = ([string]$line).Split("`t")
    switch ($fields[0]) {
        'CURRENT' { $currentRelease = $fields[1] }
        'ENV_MODE' { $envMode = $fields[1] }
        'SWAP' { $swapBytes = [long]$fields[1] }
        'DATA_NETWORK' { $dataNetworkInternal = $fields[1] -eq 'true' }
        'SERVER_TRAINING_DIRS' { $serverTrainingDirectories = [int]$fields[1] }
        'LOOPBACK_HEALTH' { $loopbackHealth = [int]$fields[1] }
        'CONTAINER_TRAINING_ABSENT' { $containerTraining[$fields[1]] = $fields[2] -eq 'true' }
        'SERVICE' {
            if ($fields.Count -ne 10) { throw "Unexpected service boundary row: $line" }
            $services += [ordered]@{
                service = $fields[1]
                imageId = $fields[2]
                platform = $fields[3]
                health = $fields[4]
                oomKilled = $fields[5] -eq 'true'
                restartCount = [int]$fields[6]
                memoryLimitBytes = [long]$fields[7]
                nanoCpus = [long]$fields[8]
                ports = $fields[9] | ConvertFrom-Json
            }
        }
        default { throw "Unexpected server boundary output: $line" }
    }
}

Assert-AcceptanceCondition -Condition ($services.Count -eq 6) -Message 'Server does not run all six Compose services.'
Assert-AcceptanceCondition -Condition ($swapBytes -ge 2000000000) -Message "Server swap is below 2 GB: $swapBytes bytes."
Assert-AcceptanceCondition -Condition ($envMode -eq '600') -Message "Server environment file mode is not 600: $envMode"
Assert-AcceptanceCondition -Condition $dataNetworkInternal -Message 'Server Compose data network is not internal.'
Assert-AcceptanceCondition -Condition ($serverTrainingDirectories -eq 0) -Message 'Server release contains a raw, interim, or processed training directory.'
Assert-AcceptanceCondition `
    -Condition ($containerTraining.Count -eq 2 -and @($containerTraining.Values | Where-Object { -not $_ }).Count -eq 0) `
    -Message 'A serving container contains raw, interim, or processed training data.'
foreach ($service in $services) {
    Assert-AcceptanceCondition `
        -Condition ([string]$service.imageId -match '^sha256:[0-9a-f]{64}$' -and
            [string]$service.platform -eq 'linux/amd64' -and
            [string]$service.health -in @('healthy', 'running') -and
            -not [bool]$service.oomKilled -and
            [int]$service.restartCount -eq 0 -and
            [long]$service.memoryLimitBytes -gt 0 -and
            [long]$service.nanoCpus -gt 0) `
        -Message "Server service failed platform, health, or resource checks: $($service.service)"
    $published = @($service.ports.PSObject.Properties | Where-Object { $null -ne $_.Value })
    if ([string]$service.service -eq 'caddy') {
        $bindings = if ($published.Count -eq 1 -and $published[0].Name -eq '80/tcp') {
            @($published[0].Value)
        }
        else {
            @()
        }
        Assert-AcceptanceCondition `
            -Condition ($bindings.Count -ge 1 -and
                @($bindings | Where-Object {
                        [string]$_.HostPort -ne '80' -or
                        [string]$_.HostIp -ne '127.0.0.1'
                    }).Count -eq 0) `
            -Message 'Caddy TCP 80 must be bound only to the server loopback interface.'
    }
    else {
        Assert-AcceptanceCondition -Condition ($published.Count -eq 0) -Message "Non-edge service publishes a server port: $($service.service)"
    }
}
Assert-AcceptanceCondition -Condition ($loopbackHealth -eq 200) -Message 'Loopback Caddy health endpoint is unavailable.'

$report = [ordered]@{
    schemaVersion = 1
    kind = 'edutwin-server-boundary-verification'
    status = 'PASSED'
    server = $Server
    verifiedAt = [DateTimeOffset]::UtcNow.ToString('o')
    currentRelease = $currentRelease
    environmentFileMode = $envMode
    swapBytes = $swapBytes
    dataNetworkInternal = $dataNetworkInternal
    publicApplicationPorts = @()
    loopbackApplicationPorts = @(80)
    loopbackHealthStatus = $loopbackHealth
    serverTrainingDirectories = $serverTrainingDirectories
    containerTrainingDataAbsent = $containerTraining
    services = $services
}
Write-AcceptanceEvidence -Value $report -Path $OutputPath
$report
