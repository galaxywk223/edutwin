[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))

function Invoke-Checked {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [Parameter(Mandatory)][string[]]$Arguments
    )

    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Contract command failed with exit code ${LASTEXITCODE}: $FilePath"
    }
}

Push-Location $repositoryRoot
try {
    $publicApi = Join-Path $repositoryRoot 'contracts/openapi/public-api.yaml'
    $modelApi = Join-Path $repositoryRoot 'contracts/openapi/model-api.yaml'
    $asyncApi = Join-Path $repositoryRoot 'contracts/asyncapi/analysis-events.yaml'
    $backendPom = Join-Path $repositoryRoot 'backend/pom.xml'

    Invoke-Checked npx @(
        '--yes', '@redocly/cli@2.38.0', 'lint',
        $publicApi,
        $modelApi
    )
    Invoke-Checked npx @(
        '--yes', '@asyncapi/cli@6.0.2', 'validate',
        $asyncApi
    )
    if ($IsWindows) {
        Invoke-Checked (Join-Path $repositoryRoot 'backend/mvnw.cmd') @(
            '-q', '-f', $backendPom, 'generate-sources'
        )
    }
    else {
        Invoke-Checked bash @(
            (Join-Path $repositoryRoot 'backend/mvnw'),
            '-q', '-f', $backendPom, 'generate-sources'
        )
    }
}
finally {
    Pop-Location
}

[pscustomobject]@{
    public_openapi = 'passed'
    model_openapi = 'passed'
    asyncapi = 'passed'
    backend_generation = 'passed'
}
