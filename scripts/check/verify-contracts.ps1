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
    Invoke-Checked npx @(
        '--yes', '@redocly/cli@2.38.0', 'lint',
        '.\contracts\openapi\public-api.yaml',
        '.\contracts\openapi\model-api.yaml'
    )
    Invoke-Checked npx @(
        '--yes', '@asyncapi/cli@6.0.2', 'validate',
        '.\contracts\asyncapi\analysis-events.yaml'
    )
    Invoke-Checked (Join-Path $repositoryRoot 'backend\mvnw.cmd') @(
        '-q', '-f', '.\backend\pom.xml', 'generate-sources'
    )
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
