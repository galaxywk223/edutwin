[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$composeFile = Join-Path $repositoryRoot 'infra\compose\test-dependencies.yaml'
$mavenWrapper = Join-Path $repositoryRoot 'backend\mvnw.cmd'

foreach ($path in @($composeFile, $mavenWrapper)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required backend verification file is missing: $path"
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

$compose = @('compose', '-f', $composeFile)
Push-Location $repositoryRoot
try {
    & docker @compose down --volumes --remove-orphans 2>$null
    Invoke-Checked docker (@($compose) + @('up', '-d', '--wait', '--wait-timeout', '120'))
    Invoke-Checked $mavenWrapper @('-q', '-f', '.\backend\pom.xml', 'clean', 'test')
}
finally {
    & docker @compose down --volumes --remove-orphans
    $cleanupExitCode = $LASTEXITCODE
    Pop-Location
    if ($cleanupExitCode -ne 0) {
        Write-Warning "Backend verification dependency cleanup exited with code $cleanupExitCode."
    }
}
