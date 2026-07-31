[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$Server,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$SshUser,
    [string]$IdentityFile = (Join-Path $HOME '.ssh\id_rsa'),
    [ValidatePattern('^/[A-Za-z0-9._/-]+$')]
    [string]$RemoteRelease = '/opt/edutwin/releases/current',
    [Parameter(Mandatory)][string]$OutputPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$IdentityFile = [System.IO.Path]::GetFullPath($IdentityFile)
$OutputPath = [System.IO.Path]::GetFullPath($OutputPath)
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
cd '__REMOTE_RELEASE__'
compose_file='infra/compose/compose.yaml'
env_file='infra/env/.env'
for service in mysql redis model-service backend frontend caddy; do
  cid=$(docker compose --env-file "$env_file" -f "$compose_file" ps -q "$service")
  test -n "$cid"
  image_id=$(docker inspect "$cid" --format '{{.Image}}')
  image_name=$(docker inspect "$cid" --format '{{.Config.Image}}')
  platform=$(docker image inspect "$image_id" --format '{{.Os}}/{{.Architecture}}')
  health=$(docker inspect "$cid" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}')
  oom=$(docker inspect "$cid" --format '{{.State.OOMKilled}}')
  restart=$(docker inspect "$cid" --format '{{.RestartCount}}')
  printf 'IMAGE\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$service" "$image_name" "$image_id" "$platform" "$health" "$oom" "$restart" "$cid"
done
docker compose --env-file "$env_file" -f "$compose_file" exec -T mysql sh -c '
  mysql --batch --skip-column-names \
    -u"$MYSQL_USER" --password="$MYSQL_PASSWORD" "$MYSQL_DATABASE" \
    -e "SELECT version, description, checksum, DATE_FORMAT(installed_on, '\''%Y-%m-%dT%H:%i:%sZ'\''), success FROM flyway_schema_history WHERE type = '\''SQL'\'' ORDER BY installed_rank;"
' | while IFS="$(printf '\t')" read -r version description checksum installed_on success; do
  printf 'MIGRATION\t%s\t%s\t%s\t%s\t%s\n' \
    "$version" "$description" "$checksum" "$installed_on" "$success"
done
'@
$remoteScript = $remoteScript.Replace('__REMOTE_RELEASE__', $RemoteRelease)

$encodedScript = [Convert]::ToBase64String(
    [Text.Encoding]::UTF8.GetBytes($remoteScript.Replace("`r", ''))
)
$output = & ssh @sshOptions $remote "printf '%s' '$encodedScript' | base64 -d | bash"
if ($LASTEXITCODE -ne 0) {
    throw "Remote runtime evidence command failed with exit code $LASTEXITCODE."
}

$images = @()
$containers = @()
$migrations = @()
foreach ($line in @($output)) {
    if ([string]::IsNullOrWhiteSpace([string]$line)) {
        continue
    }
    $fields = ([string]$line).Split("`t")
    switch ($fields[0]) {
        'IMAGE' {
            if ($fields.Count -ne 9) {
                throw "Unexpected remote image evidence row: $line"
            }
            if ($fields[3] -notmatch '^sha256:[0-9a-f]{64}$' -or
                $fields[4] -ne 'linux/amd64' -or
                $fields[5] -notin @('healthy', 'running') -or
                $fields[6] -ne 'false' -or
                [int]$fields[7] -ne 0) {
                throw "Remote service $($fields[1]) failed runtime evidence checks."
            }
            $images += [ordered]@{
                service = $fields[1]
                name = $fields[2]
                id = $fields[3]
                platform = $fields[4]
            }
            $containers += [ordered]@{
                service = $fields[1]
                health = $fields[5]
                oomKilled = $false
                restartCount = 0
                containerId = $fields[8]
            }
        }
        'MIGRATION' {
            if ($fields.Count -ne 6) {
                throw "Unexpected remote Flyway evidence row: $line"
            }
            $migrations += [ordered]@{
                version = $fields[1]
                description = $fields[2]
                checksum = [int]$fields[3]
                installedOn = $fields[4]
                success = $fields[5] -eq '1'
            }
        }
        default {
            throw "Unexpected remote runtime evidence output: $line"
        }
    }
}
if ($images.Count -ne 6 -or $migrations.Count -eq 0 -or
    @($migrations | Where-Object { -not $_.success }).Count -gt 0) {
    throw 'Remote runtime evidence is incomplete.'
}

$report = [ordered]@{
    schemaVersion = 1
    kind = 'edutwin-server-runtime-evidence'
    server = $Server
    capturedAt = [DateTimeOffset]::UtcNow.ToString('o')
    images = $images
    migrations = $migrations
    containers = $containers
}
New-Item -Path (Split-Path -Parent $OutputPath) -ItemType Directory -Force | Out-Null
$report | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $OutputPath -Encoding utf8NoBOM
$report
