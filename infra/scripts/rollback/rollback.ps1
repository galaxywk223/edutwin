[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$')]
    [string]$ImageTag,
    [string]$EnvFile,
    [string]$DatabaseBackup,
    [string]$DatabaseBackupSha256
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$composeFile = Join-Path $repositoryRoot 'infra/compose/compose.yaml'
if ([string]::IsNullOrWhiteSpace($EnvFile)) {
    $EnvFile = Join-Path $repositoryRoot 'infra/env/.env'
}
$EnvFile = [System.IO.Path]::GetFullPath($EnvFile)

if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf)) {
    throw "Environment file not found: $EnvFile"
}
if (-not $PSCmdlet.ShouldProcess('EduTwin application services', "Switch to image tag $ImageTag")) {
    return
}

$previousTag = $env:EDUTWIN_IMAGE_TAG
$env:EDUTWIN_IMAGE_TAG = $ImageTag
$compose = @('compose', '--env-file', $EnvFile, '-f', $composeFile)
try {
    & docker @compose pull backend model-service frontend
    if ($LASTEXITCODE -ne 0) {
        throw "Images for tag $ImageTag could not be pulled."
    }

    if (-not [string]::IsNullOrWhiteSpace($DatabaseBackup)) {
        $restoreArguments = @{
            BackupFile = $DatabaseBackup
            EnvFile = $EnvFile
            SkipSafetyBackup = $false
            Confirm = $false
        }
        if (-not [string]::IsNullOrWhiteSpace($DatabaseBackupSha256)) {
            $restoreArguments.ExpectedSha256 = $DatabaseBackupSha256
        }
        & (Join-Path $repositoryRoot 'infra/scripts/backup/restore.ps1') @restoreArguments
    }

    & docker @compose up -d --no-build --remove-orphans
    if ($LASTEXITCODE -ne 0) {
        throw "Compose could not activate tag $ImageTag."
    }
    & docker @compose ps
    if ($LASTEXITCODE -ne 0) {
        throw 'Compose service status could not be read.'
    }
}
finally {
    if ($null -eq $previousTag) {
        Remove-Item Env:EDUTWIN_IMAGE_TAG -ErrorAction SilentlyContinue
    }
    else {
        $env:EDUTWIN_IMAGE_TAG = $previousTag
    }
}
