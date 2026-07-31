[CmdletBinding()]
param(
    [string]$OutputPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$common = Join-Path $PSScriptRoot 'Verification.Common.ps1'
$manifestPath = Join-Path $repositoryRoot 'data\manifests\processing-manifest.json'
$sourceLockPath = Join-Path $repositoryRoot 'data\manifests\source-lock.json'
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $repositoryRoot 'reports\dist\data-reproducibility.json'
}
$OutputPath = [System.IO.Path]::GetFullPath($OutputPath)

. $common

$environmentManifest = New-AcceptanceManifest `
    -Target Local `
    -RunId 'data-reproducibility' `
    -CodeTreeSha256 ('0' * 64) `
    -StartedAt (Get-UtcTimestamp) `
    -PythonRecordPath '.codex-python-env.local.json'
$python = Resolve-ConfirmedPythonEnvironment `
    -RepositoryRoot $repositoryRoot `
    -Manifest $environmentManifest
if (-not $python.confirmed) {
    throw $python.message
}
if (-not (Test-Path -LiteralPath $sourceLockPath -PathType Leaf)) {
    throw "Source lock is missing: $sourceLockPath"
}

function Invoke-Modeling {
    param([Parameter(Mandatory)][string[]]$Arguments)

    Push-Location $python.workingDirectory
    try {
        $allArguments = @($python.prefixArguments) + $Arguments
        & $python.executable @allArguments
        if ($LASTEXITCODE -ne 0) {
            throw "Modeling command failed with exit code ${LASTEXITCODE}: $($Arguments -join ' ')"
        }
    }
    finally {
        Pop-Location
    }
}

function Get-RequiredSha256 {
    param([Parameter(Mandatory)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Required reproducibility artifact is missing: $Path"
    }
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

$arguments = @(
    '-m', 'edutwin_modeling.cli',
    'data', 'prepare',
    '--config', 'configs/data/pipeline.yaml'
)
Invoke-Modeling -Arguments $arguments
$firstSha256 = Get-RequiredSha256 -Path $manifestPath
$firstManifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json

Invoke-Modeling -Arguments $arguments
$secondSha256 = Get-RequiredSha256 -Path $manifestPath
$secondManifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json

if ($firstSha256 -ne $secondSha256) {
    throw "Data preparation is not reproducible: $firstSha256 != $secondSha256"
}
if (($firstManifest | ConvertTo-Json -Depth 100 -Compress) -ne
    ($secondManifest | ConvertTo-Json -Depth 100 -Compress)) {
    throw 'Data preparation manifests differ despite matching file hashes.'
}

Invoke-Modeling -Arguments @(
    '-m', 'edutwin_modeling.cli',
    'data', 'verify',
    '--config', 'configs/data/pipeline.yaml'
)

$report = [ordered]@{
    schemaVersion = 1
    kind = 'edutwin-data-reproducibility'
    seed = 42
    attempts = 2
    identical = $true
    firstProcessingManifestSha256 = $firstSha256
    secondProcessingManifestSha256 = $secondSha256
    sourceLockSha256 = Get-RequiredSha256 -Path $sourceLockPath
    verifiedAt = Get-UtcTimestamp
}
New-Item -Path (Split-Path -Parent $OutputPath) -ItemType Directory -Force | Out-Null
$report | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $OutputPath -Encoding utf8NoBOM
$report
