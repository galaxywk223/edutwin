[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$')]
    [string]$ImageTag,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$Server,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$SshUser,
    [string]$IdentityFile = (Join-Path $HOME '.ssh\id_rsa'),
    [string]$EnvFile = (Join-Path $PSScriptRoot '..\..\env\.env'),
    [ValidatePattern('^/[A-Za-z0-9._/-]+$')]
    [string]$RemoteReleaseRoot = '/opt/edutwin/releases',
    [string]$Registry = 'ghcr.io',
    [string]$RegistryNamespace = 'galaxywk223',
    [string]$RegistryUsername = 'galaxywk223',
    [string]$RemoteRegistryTokenFile = '/opt/edutwin/shared/ghcr-read-token',
    [ValidateSet('Ghcr', 'Offline')][string]$Transport = 'Ghcr',
    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
$composeFile = Join-Path $repositoryRoot 'infra\compose\compose.yaml'
$caddyFile = Join-Path $repositoryRoot 'infra\caddy\Caddyfile'
$resolvedEnvFile = [System.IO.Path]::GetFullPath($EnvFile)
$resolvedIdentity = [System.IO.Path]::GetFullPath($IdentityFile)

foreach ($required in @($composeFile, $caddyFile, $resolvedEnvFile, $resolvedIdentity)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Required deployment file is missing: '$required'."
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

function Invoke-CheckedWithRetry {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [Parameter(Mandatory)][string[]]$Arguments,
        [int]$MaximumAttempts = 3
    )

    for ($attempt = 1; $attempt -le $MaximumAttempts; $attempt++) {
        & $FilePath @Arguments
        if ($LASTEXITCODE -eq 0) { return }
        if ($attempt -lt $MaximumAttempts) {
            Start-Sleep -Seconds (5 * $attempt)
        }
    }
    throw "Command failed after $MaximumAttempts attempts: $FilePath"
}

function Send-ResumableSftp {
    param(
        [Parameter(Mandatory)][string]$LocalPath,
        [Parameter(Mandatory)]
        [ValidatePattern('^/[A-Za-z0-9._/-]+$')]
        [string]$RemotePath,
        [Parameter(Mandatory)][string]$Remote,
        [Parameter(Mandatory)][string[]]$SshOptions,
        [int]$MaximumAttempts = 30
    )

    $resolved = [System.IO.Path]::GetFullPath($LocalPath)
    $expectedBytes = (Get-Item -LiteralPath $resolved).Length
    $sftpPath = $resolved.Replace('\', '/')
    & ssh @SshOptions $Remote "if [ ! -e '$RemotePath' ]; then umask 077; : > '$RemotePath'; fi"
    if ($LASTEXITCODE -ne 0) {
        throw "Remote upload target could not be prepared: $RemotePath"
    }
    for ($attempt = 1; $attempt -le $MaximumAttempts; $attempt++) {
        "reput `"$sftpPath`" $RemotePath" |
            & sftp -q -b - @SshOptions $Remote 2>$null
        $sizeOutput = & ssh @SshOptions $Remote `
            "stat -c %s '$RemotePath' 2>/dev/null || echo 0" 2>$null
        $remoteBytes = if ($LASTEXITCODE -eq 0 -and @($sizeOutput).Count -gt 0) {
            [long]@($sizeOutput)[-1]
        }
        else {
            0L
        }
        if ($remoteBytes -eq $expectedBytes) {
            return
        }
        if ($remoteBytes -gt $expectedBytes) {
            throw "Remote upload exceeds the local artifact size: $RemotePath"
        }
        Start-Sleep -Seconds 1
    }
    throw "Resumable SFTP upload did not complete after $MaximumAttempts attempts: $RemotePath"
}

function Get-CodeTreeHash {
    $paths = @(& git -C $repositoryRoot ls-files | Sort-Object)
    if ($LASTEXITCODE -ne 0 -or $paths.Count -eq 0) {
        throw 'The tracked code tree contains no hashable files.'
    }
    $digest = [System.Security.Cryptography.IncrementalHash]::CreateHash(
        [System.Security.Cryptography.HashAlgorithmName]::SHA256
    )
    try {
        foreach ($relativePath in $paths) {
            $normalizedPath = $relativePath.Replace('\', '/')
            $pathBytes = [System.Text.Encoding]::UTF8.GetBytes($normalizedPath)
            $digest.AppendData([System.BitConverter]::GetBytes($pathBytes.Length))
            $digest.AppendData($pathBytes)
            $filePath = Join-Path $repositoryRoot $relativePath
            $stream = [System.IO.File]::OpenRead($filePath)
            try {
                $digest.AppendData([System.BitConverter]::GetBytes($stream.Length))
                $buffer = [byte[]]::new(1MB)
                while (($read = $stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
                    $digest.AppendData($buffer, 0, $read)
                }
            }
            finally {
                $stream.Dispose()
            }
        }
        return [System.Convert]::ToHexString($digest.GetHashAndReset()).ToLowerInvariant()
    }
    finally {
        $digest.Dispose()
    }
}

function Set-EnvironmentValue {
    param(
        [Parameter(Mandatory)][System.Collections.Generic.List[string]]$Lines,
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Value
    )

    for ($index = 0; $index -lt $Lines.Count; $index++) {
        if ($Lines[$index].StartsWith("$Name=")) {
            $Lines[$index] = "$Name=$Value"
            return
        }
    }
    $Lines.Add("$Name=$Value")
}

function Get-ImageRecord {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Image,
        [Parameter(Mandatory)][ValidateSet('ghcr', 'docker-hub', 'local-archive')][string]$Source
    )

    $json = & docker image inspect $Image --format '{{json .}}'
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($json)) {
        throw "Image inspection failed: '$Image'."
    }
    $value = $json | ConvertFrom-Json
    $platform = & docker image inspect $Image --format '{{.Os}}/{{.Architecture}}'
    if ($LASTEXITCODE -ne 0 -or $platform -ne 'linux/amd64') {
        throw "Image '$Image' is not linux/amd64: '$platform'."
    }
    $digest = [string]$value.Id
    if ($Source -ne 'local-archive') {
        $repository = $Image.Substring(0, $Image.LastIndexOf(':'))
        $repoDigest = @($value.RepoDigests | Where-Object { $_.StartsWith("$repository@") }) |
            Select-Object -First 1
        if ([string]::IsNullOrWhiteSpace($repoDigest)) {
            throw "Registry digest is unavailable for '$Image'. Push or pull the image first."
        }
        $digest = $repoDigest.Substring($repoDigest.IndexOf('@') + 1)
    }
    return [ordered]@{
        name = $Name
        image = $Image
        digest = $digest
        id = [string]$value.Id
        platform = $platform
        source = $Source
    }
}

$vcsRef = (& git -C $repositoryRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($vcsRef)) {
    throw 'The Git revision could not be resolved.'
}
if (@(& git -C $repositoryRoot status --porcelain --untracked-files=no).Count -ne 0) {
    throw 'Deployment requires a clean Git working tree.'
}

$offline = $Transport -eq 'Offline'
$repositories = [ordered]@{
    backend = if ($offline) { 'edutwin/backend' } else { "$Registry/$RegistryNamespace/edutwin-backend" }
    model = if ($offline) { 'edutwin/model-service' } else { "$Registry/$RegistryNamespace/edutwin-model-service" }
    frontend = if ($offline) { 'edutwin/frontend' } else { "$Registry/$RegistryNamespace/edutwin-frontend" }
}
$applicationImages = [ordered]@{
    backend = "$($repositories.backend):$ImageTag"
    model = "$($repositories.model):$ImageTag"
    frontend = "$($repositories.frontend):$ImageTag"
}
$baseImages = [ordered]@{
    mysql = 'mysql:8.4.5'
    redis = 'redis:7.4.5-alpine'
    caddy = 'caddy:2.10.0-alpine'
}

$managedEnvironment = @(
    'DOCKER_DEFAULT_PLATFORM',
    'EDUTWIN_IMAGE_TAG',
    'EDUTWIN_VCS_REF',
    'EDUTWIN_BACKEND_IMAGE',
    'EDUTWIN_MODEL_IMAGE',
    'EDUTWIN_FRONTEND_IMAGE'
)
$previousEnvironment = @{}
foreach ($name in $managedEnvironment) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

try {
    $env:DOCKER_DEFAULT_PLATFORM = 'linux/amd64'
    $env:EDUTWIN_IMAGE_TAG = $ImageTag
    $env:EDUTWIN_VCS_REF = $vcsRef
    $env:EDUTWIN_BACKEND_IMAGE = $repositories.backend
    $env:EDUTWIN_MODEL_IMAGE = $repositories.model
    $env:EDUTWIN_FRONTEND_IMAGE = $repositories.frontend

    if (-not $SkipBuild) {
        Invoke-CheckedWithRetry docker @(
            'compose', '--env-file', $resolvedEnvFile, '-f', $composeFile,
            'build', '--pull', 'backend', 'model-service', 'frontend'
        )
    }
    if (-not $offline) {
        foreach ($image in $applicationImages.Values) {
            Invoke-CheckedWithRetry docker @('push', $image)
        }
    }
    foreach ($image in $baseImages.Values) {
        Invoke-CheckedWithRetry docker @('pull', '--platform', 'linux/amd64', $image)
    }

    $imageRecords = @(
        Get-ImageRecord -Name 'backend' -Image $applicationImages.backend -Source $(if ($offline) { 'local-archive' } else { 'ghcr' })
        Get-ImageRecord -Name 'model-service' -Image $applicationImages.model -Source $(if ($offline) { 'local-archive' } else { 'ghcr' })
        Get-ImageRecord -Name 'frontend' -Image $applicationImages.frontend -Source $(if ($offline) { 'local-archive' } else { 'ghcr' })
        Get-ImageRecord -Name 'mysql' -Image $baseImages.mysql -Source docker-hub
        Get-ImageRecord -Name 'redis' -Image $baseImages.redis -Source docker-hub
        Get-ImageRecord -Name 'caddy' -Image $baseImages.caddy -Source docker-hub
    )
}
finally {
    foreach ($name in $managedEnvironment) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
}

$releaseId = '{0}-{1}' -f $ImageTag, ([DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ'))
$stagingRoot = Join-Path $repositoryRoot ".runtime\deploy\$releaseId"
$releaseRoot = Join-Path $stagingRoot 'release'
New-Item -Path (Join-Path $releaseRoot 'infra\compose') -ItemType Directory -Force | Out-Null
New-Item -Path (Join-Path $releaseRoot 'infra\caddy') -ItemType Directory -Force | Out-Null
New-Item -Path (Join-Path $releaseRoot 'infra\env') -ItemType Directory -Force | Out-Null
Copy-Item -LiteralPath $composeFile -Destination (Join-Path $releaseRoot 'infra\compose\compose.yaml')
Copy-Item -LiteralPath $caddyFile -Destination (Join-Path $releaseRoot 'infra\caddy\Caddyfile')

$envLines = [System.Collections.Generic.List[string]]::new()
[System.IO.File]::ReadAllLines($resolvedEnvFile) | ForEach-Object { $envLines.Add($_) }
Set-EnvironmentValue -Lines $envLines -Name 'EDUTWIN_IMAGE_TAG' -Value $ImageTag
Set-EnvironmentValue -Lines $envLines -Name 'EDUTWIN_VCS_REF' -Value $vcsRef
Set-EnvironmentValue -Lines $envLines -Name 'EDUTWIN_BACKEND_IMAGE' -Value $repositories.backend
Set-EnvironmentValue -Lines $envLines -Name 'EDUTWIN_MODEL_IMAGE' -Value $repositories.model
Set-EnvironmentValue -Lines $envLines -Name 'EDUTWIN_FRONTEND_IMAGE' -Value $repositories.frontend
[System.IO.File]::WriteAllText(
    (Join-Path $releaseRoot 'infra\env\.env'),
    ([string]::Join("`n", $envLines) + "`n"),
    [System.Text.UTF8Encoding]::new($false)
)

$archivePath = $null
$archiveSha256 = $null
if ($offline) {
    $tarPath = Join-Path $stagingRoot 'application-images.tar'
    $archivePath = "$tarPath.gz"
    $saveArguments = @('save', '--output', $tarPath) + @($applicationImages.Values)
    Invoke-Checked docker $saveArguments
    Invoke-Checked gzip @('-f', '-6', $tarPath)
    $archiveSha256 = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
}

$manifest = [ordered]@{
    schemaVersion = 2
    releaseId = $releaseId
    imageTag = $ImageTag
    vcsRef = $vcsRef
    codeTreeSha256 = Get-CodeTreeHash
    transport = [ordered]@{
        type = if ($offline) { 'offline-docker-archive' } else { 'ghcr' }
        registry = if ($offline) { $null } else { $Registry }
        namespace = if ($offline) { $null } else { $RegistryNamespace }
        archiveSha256 = $archiveSha256
    }
    images = $imageRecords
}
$manifestPath = Join-Path $releaseRoot 'release-manifest.json'
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestPath -Encoding utf8NoBOM

$remote = "${SshUser}@${Server}"
$remoteRelease = "$RemoteReleaseRoot/$releaseId"
$sshOptions = @(
    '-o', 'BatchMode=yes',
    '-o', 'StrictHostKeyChecking=yes',
    '-o', 'ServerAliveInterval=15',
    '-o', 'ServerAliveCountMax=20',
    '-i', $resolvedIdentity
)
Invoke-Checked ssh @($sshOptions + @(
        $remote,
        "install -d -m 0750 '$remoteRelease/infra/compose' '$remoteRelease/infra/caddy' '$remoteRelease/infra/env'"
    ))

$uploads = @(
    @{ Local = Join-Path $releaseRoot 'infra\compose\compose.yaml'; Remote = "$remoteRelease/infra/compose/compose.yaml" },
    @{ Local = Join-Path $releaseRoot 'infra\caddy\Caddyfile'; Remote = "$remoteRelease/infra/caddy/Caddyfile" },
    @{ Local = Join-Path $releaseRoot 'infra\env\.env'; Remote = "$remoteRelease/infra/env/.env" },
    @{ Local = $manifestPath; Remote = "$remoteRelease/release-manifest.json" }
)
foreach ($upload in $uploads) {
    Invoke-Checked scp @(
        '-q',
        '-o', 'BatchMode=yes',
        '-o', 'StrictHostKeyChecking=yes',
        '-o', 'ServerAliveInterval=15',
        '-o', 'ServerAliveCountMax=20',
        '-i', $resolvedIdentity,
        $upload.Local,
        "${remote}:$($upload.Remote)"
    )
}

if ($offline) {
    Send-ResumableSftp `
        -LocalPath $archivePath `
        -RemotePath "$remoteRelease/application-images.tar.gz" `
        -Remote $remote `
        -SshOptions $sshOptions
}

$remoteCommand = if ($offline) { @"
set -eu
cd '$remoteRelease'
test "`$(sha256sum application-images.tar.gz | awk '{print `$1}')" = '$archiveSha256'
gzip -dc application-images.tar.gz | docker load >/dev/null
chmod 600 infra/env/.env
docker compose --env-file infra/env/.env -f infra/compose/compose.yaml pull mysql redis caddy
docker compose --env-file infra/env/.env -f infra/compose/compose.yaml up -d --no-build --wait --wait-timeout 1800 --remove-orphans
ln -sfn '$remoteRelease' '$RemoteReleaseRoot/current'
"@ } else { @"
set -eu
test -s '$RemoteRegistryTokenFile'
chmod 600 '$RemoteRegistryTokenFile'
cat '$RemoteRegistryTokenFile' | docker login '$Registry' --username '$RegistryUsername' --password-stdin >/dev/null
cd '$remoteRelease'
chmod 600 infra/env/.env
docker compose --env-file infra/env/.env -f infra/compose/compose.yaml pull mysql redis caddy backend model-service frontend
docker compose --env-file infra/env/.env -f infra/compose/compose.yaml up -d --no-build --wait --wait-timeout 1800 --remove-orphans
ln -sfn '$remoteRelease' '$RemoteReleaseRoot/current'
docker logout '$Registry' >/dev/null
"@ }
$encodedRemoteCommand = [Convert]::ToBase64String(
    [Text.Encoding]::UTF8.GetBytes($remoteCommand.Replace("`r", ''))
)
Invoke-Checked ssh @(
    $sshOptions + @(
        $remote,
        "printf '%s' '$encodedRemoteCommand' | base64 -d | bash"
    )
)

$remoteMetadata = & ssh @sshOptions $remote (
    "docker image inspect " + (($imageRecords | ForEach-Object image) -join ' ') +
    " --format '{{.Id}}`t{{json .RepoDigests}}'"
)
if ($LASTEXITCODE -ne 0) {
    throw 'Remote image digest inspection failed.'
}
$remoteRows = @($remoteMetadata)
if ($remoteRows.Count -ne $imageRecords.Count) {
    throw 'Remote image count differs from the release manifest.'
}
for ($index = 0; $index -lt $imageRecords.Count; $index++) {
    $expected = [string]$imageRecords[$index].digest
    $fields = ([string]$remoteRows[$index]).Split("`t", 2)
    $matches = if ([string]$imageRecords[$index].source -eq 'local-archive') {
        $fields.Count -eq 2 -and $fields[0] -eq $expected
    }
    else {
        $fields.Count -eq 2 -and @($fields[1] | ConvertFrom-Json | Where-Object { $_.EndsWith("@$expected") }).Count -gt 0
    }
    if (-not $matches) {
        throw "Remote digest differs for '$($imageRecords[$index].image)'."
    }
}

[pscustomobject]@{
    release_id = $releaseId
    remote_release = $remoteRelease
    code_tree_sha256 = $manifest.codeTreeSha256
    image_digests_match = $true
    manifest = $manifestPath
    transport = $manifest.transport.type
}
