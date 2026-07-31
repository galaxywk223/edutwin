[CmdletBinding()]
param(
    [ValidateSet('Local', 'Server', 'All')]
    [string]$Target = 'Local',
    [string]$EnvFile,
    [string]$Server,
    [string]$SshUser,
    [string]$ServerBaseUrl,
    [string]$IdentityFile = (Join-Path $HOME '.ssh\id_rsa')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$common = Join-Path $repositoryRoot 'scripts\verify\Verification.Common.ps1'
$schemaPath = Join-Path $repositoryRoot 'tests\fixtures\acceptance-manifest.schema.json'
$manifestPath = Join-Path $repositoryRoot 'reports\dist\acceptance-manifest.json'
$runId = [DateTimeOffset]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')
$evidenceDirectory = Join-Path $repositoryRoot ".runtime\verification\$runId"
$pythonRecordPath = Join-Path $repositoryRoot '.codex-python-env.local.json'
if ([string]::IsNullOrWhiteSpace($EnvFile)) {
    $EnvFile = Join-Path $repositoryRoot 'infra\env\.env'
}
$EnvFile = [System.IO.Path]::GetFullPath($EnvFile)
$IdentityFile = [System.IO.Path]::GetFullPath($IdentityFile)

if ($Target -in @('Server', 'All')) {
    foreach ($requiredRemoteValue in @{
            Server = $Server
            SshUser = $SshUser
            ServerBaseUrl = $ServerBaseUrl
        }.GetEnumerator()) {
        if ([string]::IsNullOrWhiteSpace([string]$requiredRemoteValue.Value)) {
            throw "$($requiredRemoteValue.Key) is required for server verification."
        }
    }
    $serverUri = $null
    if (-not [Uri]::TryCreate($ServerBaseUrl, [UriKind]::Absolute, [ref]$serverUri) -or
        $serverUri.Scheme -notin @('http', 'https') -or
        $serverUri.DnsSafeHost -notin @('localhost', '127.0.0.1', '::1')) {
        throw 'ServerBaseUrl must target a local SSH tunnel endpoint.'
    }
}

if (-not (Test-Path -LiteralPath $common -PathType Leaf)) {
    throw "Verification helper is missing: $common"
}
. $common

$startedAt = Get-UtcTimestamp
$codeTreeSha256 = Get-CodeTreeHash -RepositoryRoot $repositoryRoot
$manifest = New-AcceptanceManifest `
    -Target $Target `
    -RunId $runId `
    -CodeTreeSha256 $codeTreeSha256 `
    -StartedAt $startedAt `
    -PythonRecordPath (Get-RepositoryRelativePath `
        -Path $pythonRecordPath `
        -RepositoryRoot $repositoryRoot)
$python = Resolve-ConfirmedPythonEnvironment `
    -RepositoryRoot $repositoryRoot `
    -Manifest $manifest

function Invoke-RequiredScript {
    param(
        [Parameter(Mandatory)][string]$Id,
        [Parameter(Mandatory)][string]$Category,
        [Parameter(Mandatory)][ValidateSet('Shared', 'Local', 'Server')][string]$Scope,
        [Parameter(Mandatory)][string]$RelativePath,
        [string[]]$Arguments = @()
    )

    Invoke-ScriptGate `
        -Manifest $manifest `
        -RepositoryRoot $repositoryRoot `
        -EvidenceDirectory $evidenceDirectory `
        -Id $Id `
        -Category $Category `
        -Scope $Scope `
        -ScriptPath (Join-Path $repositoryRoot $RelativePath) `
        -Arguments $Arguments `
        -WorkingDirectory $repositoryRoot | Out-Null
}

function Invoke-RequiredCommand {
    param(
        [Parameter(Mandatory)][string]$Id,
        [Parameter(Mandatory)][string]$Category,
        [Parameter(Mandatory)][ValidateSet('Shared', 'Local', 'Server')][string]$Scope,
        [Parameter(Mandatory)][string]$FilePath,
        [string[]]$Arguments = @(),
        [string]$WorkingDirectory = $repositoryRoot
    )

    Invoke-ExternalGate `
        -Manifest $manifest `
        -RepositoryRoot $repositoryRoot `
        -EvidenceDirectory $evidenceDirectory `
        -Id $Id `
        -Category $Category `
        -Scope $Scope `
        -FilePath $FilePath `
        -Arguments $Arguments `
        -WorkingDirectory $WorkingDirectory | Out-Null
}

function Invoke-RequiredPython {
    param(
        [Parameter(Mandatory)][string]$Id,
        [Parameter(Mandatory)][string]$Category,
        [Parameter(Mandatory)][string[]]$Arguments
    )

    Invoke-PythonGate `
        -Manifest $manifest `
        -PythonEnvironment $python `
        -RepositoryRoot $repositoryRoot `
        -EvidenceDirectory $evidenceDirectory `
        -Id $Id `
        -Category $Category `
        -Arguments $Arguments `
        -WorkingDirectory (Join-Path $repositoryRoot 'modeling') | Out-Null
}

function Set-FileEvidence {
    param(
        [Parameter(Mandatory)][System.Collections.IDictionary]$Container,
        [Parameter(Mandatory)][string]$Property,
        [Parameter(Mandatory)][string]$Path
    )

    if (Test-Path -LiteralPath $Path -PathType Leaf) {
        $Container[$Property] = Get-FileReference `
            -Path $Path `
            -RepositoryRoot $repositoryRoot
    }
}

function Get-RunEvidencePath {
    param([Parameter(Mandatory)][string]$Name)

    return Join-Path $evidenceDirectory $Name
}

function ConvertTo-MetricEvidence {
    param([Parameter(Mandatory)][object]$Metrics)

    return @(
        $Metrics.PSObject.Properties |
            Where-Object {
                $null -ne $_.Value -and
                $_.Value -is [System.ValueType] -and
                $_.Value -isnot [bool]
            } |
            Sort-Object Name |
            ForEach-Object {
                [ordered]@{
                    name = [string]$_.Name
                    value = [double]$_.Value
                }
            }
    )
}

function Collect-DatasetEvidence {
    param(
        [Parameter(Mandatory)][string]$SourceLockPath,
        [Parameter(Mandatory)][string]$ProcessingManifestPath
    )

    if (-not (Test-Path -LiteralPath $SourceLockPath -PathType Leaf) -or
        -not (Test-Path -LiteralPath $ProcessingManifestPath -PathType Leaf)) {
        return
    }
    try {
        $sourceLock = Get-Content -LiteralPath $SourceLockPath -Raw | ConvertFrom-Json
        $processing = Get-Content -LiteralPath $ProcessingManifestPath -Raw | ConvertFrom-Json
        $processingSha = (Get-FileHash -LiteralPath $ProcessingManifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
        $assistCanonicalSha = [string]$sourceLock.assistments_2009_2010_skill_builder.canonical_corrected_nonfolded.file.sha256
        $ouladArchiveSha = [string]$sourceLock.oulad.archive.sha256
        $demoManifestSha = [string]$processing.demo.manifest_sha256
        $ouladManifestSha = [string]$processing.oulad.manifest_sha256
        $sourceLockSha = (Get-FileHash -LiteralPath $SourceLockPath -Algorithm SHA256).Hash.ToLowerInvariant()
        foreach ($hash in @(
                $assistCanonicalSha, $ouladArchiveSha, $demoManifestSha,
                $ouladManifestSha, $sourceLockSha, $processingSha
            )) {
            if ($hash -notmatch '^[a-f0-9]{64}$') {
                throw 'A dataset evidence hash is missing or invalid.'
            }
        }
        $manifest.data.datasets = @(
            [ordered]@{
                sourceId = 'ASSISTMENTS_2009_2010_SKILL_BUILDER_CORRECTED'
                version = [string]$processing.assistments.dataset
                manifestSha256 = $processingSha
                sourceSha256s = @($assistCanonicalSha, $sourceLockSha)
            },
            [ordered]@{
                sourceId = 'OULAD'
                version = [string]$processing.oulad.feature_contract_version
                manifestSha256 = $ouladManifestSha
                sourceSha256s = @($ouladArchiveSha, $sourceLockSha)
            },
            [ordered]@{
                sourceId = 'EDUTWIN_DEMO'
                version = 'synthetic-demo-v6'
                manifestSha256 = $demoManifestSha
                sourceSha256s = @($assistCanonicalSha, $ouladArchiveSha)
            }
        )
        if (@($manifest.data.datasets | Where-Object {
                    [string]::IsNullOrWhiteSpace($_.version)
                }).Count -ne 0) {
            throw 'A dataset version identifier is missing.'
        }
    }
    catch {
        Add-OpenItem `
            -Manifest $manifest `
            -Id 'evidence.dataset-versions' `
            -Scope Shared `
            -Message $_.Exception.Message `
            -Evidence @('data/manifests/source-lock.json', 'data/manifests/processing-manifest.json')
    }
}

function Collect-ModelEvidence {
    param(
        [Parameter(Mandatory)][ValidateSet('KNOWLEDGE', 'RISK')][string]$Task,
        [Parameter(Mandatory)][string]$FreezePath,
        [Parameter(Mandatory)][string]$MetricsPath
    )

    if (-not (Test-Path -LiteralPath $FreezePath -PathType Leaf) -or
        -not (Test-Path -LiteralPath $MetricsPath -PathType Leaf)) {
        return
    }
    try {
        $freeze = Get-Content -LiteralPath $FreezePath -Raw | ConvertFrom-Json
        $metrics = Get-Content -LiteralPath $MetricsPath -Raw | ConvertFrom-Json
        $freezeSha = (Get-FileHash -LiteralPath $FreezePath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ([string]$metrics.freezeManifestSha256 -ne $freezeSha) {
            throw "$Task test metrics are not bound to the recorded freeze manifest."
        }
        $winner = if ($Task -eq 'KNOWLEDGE') {
            [string]$freeze.selection.nextCorrectPredictor.family
        }
        else {
            [string]$freeze.selection.winner
        }
        foreach ($candidateProperty in $freeze.candidates.PSObject.Properties) {
            $family = [string]$candidateProperty.Name
            $candidate = $candidateProperty.Value
            $testCandidate = if ($Task -eq 'KNOWLEDGE') {
                $metrics.candidates.$family
            }
            else {
                $metrics.metrics.$family
            }
            if ($null -eq $testCandidate) {
                throw "$Task test metrics omit candidate $family."
            }
            $modelVersion = [string]$candidate.modelVersion
            if ($Task -eq 'KNOWLEDGE' -and
                [string]$testCandidate.modelVersion -ne $modelVersion) {
                throw "KNOWLEDGE test metrics use a different model version for $family."
            }
            if ($Task -eq 'RISK' -and
                [string]$metrics.candidateBindings.$family.modelVersion -ne $modelVersion) {
                throw "RISK test metrics use a different model version for $family."
            }
            $artifactHashes = if ($Task -eq 'KNOWLEDGE') {
                @(
                    [string]$candidate.modelArtifactSha256,
                    ([string]$candidate.calibratorVersion).Replace('sha256:', '')
                )
            }
            else {
                @(
                    [string]$candidate.modelArtifact.sha256,
                    [string]$candidate.calibratorArtifact.sha256
                )
            }
            if (@($artifactHashes | Where-Object { $_ -notmatch '^[a-f0-9]{64}$' }).Count -ne 0) {
                throw "$Task candidate $family has an invalid artifact binding."
            }
            $metricObject = if ($Task -eq 'KNOWLEDGE') {
                $testCandidate.metrics
            }
            else {
                $testCandidate
            }
            $metricEvidence = @(ConvertTo-MetricEvidence -Metrics $metricObject)
            if ($metricEvidence.Count -eq 0) {
                throw "$Task candidate $family has no numeric test metrics."
            }
            $manifest.models.versions = @($manifest.models.versions) + [ordered]@{
                task = $Task
                family = $family
                modelVersion = $modelVersion
                selected = ($family -eq $winner -or ($Task -eq 'KNOWLEDGE' -and $family -eq 'BKT'))
                artifactSha256s = @($artifactHashes | Select-Object -Unique)
                metrics = $metricEvidence
            }
        }
        $testSetKind = if ($Task -eq 'KNOWLEDGE') { 'EVENTS' } else { 'STUDENTS' }
        $testSetSha = if ($Task -eq 'KNOWLEDGE') {
            [string]$metrics.eventSetSha256
        }
        else {
            [string]$metrics.testStudentSetSha256
        }
        if ($testSetSha -notmatch '^[a-f0-9]{64}$') {
            throw "$Task test cohort hash is missing or invalid."
        }
        $manifest.models.testEvaluations = @($manifest.models.testEvaluations) + [ordered]@{
            task = $Task
            freezeManifestSha256 = $freezeSha
            testSetKind = $testSetKind
            testSetSha256 = $testSetSha
        }
    }
    catch {
        Add-OpenItem `
            -Manifest $manifest `
            -Id "evidence.models.$($Task.ToLowerInvariant())" `
            -Scope Shared `
            -Message $_.Exception.Message `
            -Evidence @(
                (Get-RepositoryRelativePath -Path $FreezePath -RepositoryRoot $repositoryRoot),
                (Get-RepositoryRelativePath -Path $MetricsPath -RepositoryRoot $repositoryRoot)
            )
    }
}

function Collect-ResultEvidence {
    $local = $manifest.results.local
    $serverResult = $manifest.results.server
    $files = @(
        @($local, 'liveContracts', 'local-live-contracts.json'),
        @($local, 'closedLoop', 'local-closed-loop.json'),
        @($local, 'deepSeekToolCall', 'local-deepseek-tool-call.json'),
        @($local, 'resilience', 'local-resilience.json'),
        @($local, 'performance', 'local-performance.json'),
        @($local, 'restartRecovery', 'local-restart-recovery.json'),
        @($local, 'modelRollback', 'local-model-rollback.json'),
        @($local, 'runtime', 'local-runtime.json'),
        @($serverResult, 'boundary', 'server-boundary.json'),
        @($serverResult, 'liveContracts', 'server-live-contracts.json'),
        @($serverResult, 'closedLoop', 'server-closed-loop.json'),
        @($serverResult, 'performance', 'server-performance.json'),
        @($serverResult, 'restartRecovery', 'server-restart-recovery.json'),
        @($serverResult, 'rollback', 'server-rollback.json'),
        @($serverResult, 'runtime', 'server-runtime.json')
    )
    foreach ($entry in $files) {
        Set-FileEvidence `
            -Container $entry[0] `
            -Property ([string]$entry[1]) `
            -Path (Get-RunEvidencePath -Name ([string]$entry[2]))
    }
}

function Collect-StaticEvidence {
    $sourceLockPath = Join-Path $repositoryRoot 'data\manifests\source-lock.json'
    $processingManifestPath = Join-Path $repositoryRoot 'data\manifests\processing-manifest.json'
    $knowledgeFreezePath = Join-Path $repositoryRoot 'modeling\artifacts\manifests\knowledge-freeze.json'
    $knowledgeMetricsPath = Join-Path $repositoryRoot 'modeling\artifacts\manifests\knowledge-test-metrics.json'
    $riskFreezePath = Join-Path $repositoryRoot 'modeling\artifacts\manifests\risk-freeze.json'
    $riskMetricsPath = Join-Path $repositoryRoot 'modeling\artifacts\manifests\risk-test-metrics.json'
    Set-FileEvidence `
        -Container $manifest.data `
        -Property sourceLock `
        -Path $sourceLockPath
    Set-FileEvidence `
        -Container $manifest.data `
        -Property processingManifest `
        -Path $processingManifestPath
    Set-FileEvidence `
        -Container $manifest.models `
        -Property knowledgeFreeze `
        -Path $knowledgeFreezePath
    Set-FileEvidence `
        -Container $manifest.models `
        -Property knowledgeTestMetrics `
        -Path $knowledgeMetricsPath
    Set-FileEvidence `
        -Container $manifest.models `
        -Property riskFreeze `
        -Path $riskFreezePath
    Set-FileEvidence `
        -Container $manifest.models `
        -Property riskTestMetrics `
        -Path $riskMetricsPath

    Collect-DatasetEvidence `
        -SourceLockPath $sourceLockPath `
        -ProcessingManifestPath $processingManifestPath
    Collect-ModelEvidence `
        -Task KNOWLEDGE `
        -FreezePath $knowledgeFreezePath `
        -MetricsPath $knowledgeMetricsPath
    Collect-ModelEvidence `
        -Task RISK `
        -FreezePath $riskFreezePath `
        -MetricsPath $riskMetricsPath

    $artifactRoot = Join-Path $repositoryRoot 'modeling\artifacts'
    if (Test-Path -LiteralPath $artifactRoot -PathType Container) {
        $manifest.models.artifacts = @(
            Get-ChildItem -LiteralPath $artifactRoot -Recurse -File |
                Where-Object {
                    $_.Name -ne '.gitkeep' -and
                    $_.Extension -notin @('.tmp', '.log')
                } |
                Sort-Object FullName |
                ForEach-Object {
                    Get-FileReference -Path $_.FullName -RepositoryRoot $repositoryRoot
                }
        )
    }
}

function Import-RuntimeEvidence {
    param(
        [Parameter(Mandatory)][ValidateSet('Local', 'Server')][string]$Scope,
        [Parameter(Mandatory)][string]$Path
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return
    }
    try {
        $evidence = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
        foreach ($image in @($evidence.images)) {
            $manifest.images = @($manifest.images) + [ordered]@{
                scope = $Scope
                name = [string]$image.name
                id = [string]$image.id
                platform = [string]$image.platform
            }
        }
        foreach ($migration in @($evidence.migrations)) {
            $record = [ordered]@{
                scope = $Scope
                version = [string]$migration.version
                description = [string]$migration.description
                checksum = [int]$migration.checksum
                installedOn = [string]$migration.installedOn
                success = [bool]$migration.success
            }
            if ($Scope -eq 'Local') {
                $manifest.migrations.local = @($manifest.migrations.local) + $record
            }
            else {
                $manifest.migrations.server = @($manifest.migrations.server) + $record
            }
        }
    }
    catch {
        Add-OpenItem `
            -Manifest $manifest `
            -Id "runtime-evidence.$($Scope.ToLowerInvariant())" `
            -Scope $Scope `
            -Message $_.Exception.Message `
            -Evidence @((Get-RepositoryRelativePath -Path $Path -RepositoryRoot $repositoryRoot))
    }
}

try {
    Invoke-RequiredScript `
        -Id 'contract.schemas' `
        -Category 'contracts' `
        -Scope Shared `
        -RelativePath 'scripts\check\verify-contracts.ps1'
    Invoke-RequiredScript `
        -Id 'backend.integration' `
        -Category 'backend' `
        -Scope Shared `
        -RelativePath 'scripts\verify\verify-backend.ps1'

    Invoke-RequiredPython `
        -Id 'python.ruff' `
        -Category 'modeling' `
        -Arguments @('-m', 'ruff', 'check', 'src', 'tests')
    Invoke-RequiredPython `
        -Id 'python.mypy' `
        -Category 'modeling' `
        -Arguments @('-m', 'mypy', 'src')
    Invoke-RequiredPython `
        -Id 'python.pytest' `
        -Category 'modeling' `
        -Arguments @('-m', 'pytest', '-q')

    Invoke-RequiredScript `
        -Id 'data.sources' `
        -Category 'data' `
        -Scope Shared `
        -RelativePath 'scripts\data\download-datasets.ps1'
    Invoke-RequiredScript `
        -Id 'data.reproducibility' `
        -Category 'data' `
        -Scope Shared `
        -RelativePath 'scripts\verify\verify-data-reproducibility.ps1'
    Invoke-RequiredPython `
        -Id 'models.knowledge' `
        -Category 'models' `
        -Arguments @(
            '-m', 'edutwin_modeling.cli',
            'knowledge', 'verify',
            '--config', 'configs/knowledge/training.yaml'
        )
    Invoke-RequiredPython `
        -Id 'models.risk' `
        -Category 'models' `
        -Arguments @(
            '-m', 'edutwin_modeling.cli',
            'risk', 'verify',
            '--config', 'configs/risk/training.yaml'
        )
    Invoke-RequiredPython `
        -Id 'demo.bootstrap' `
        -Category 'data' `
        -Arguments @(
            '-m', 'edutwin_modeling.cli',
            'demo', 'bootstrap',
            '--config', 'configs/demo/bootstrap.yaml'
        )
    Invoke-RequiredPython `
        -Id 'demo.bootstrap-verify' `
        -Category 'data' `
        -Arguments @(
            '-m', 'edutwin_modeling.cli',
            'demo', 'verify-bootstrap',
            '--config', 'configs/demo/bootstrap.yaml'
        )
    Invoke-RequiredScript `
        -Id 'demo.registry' `
        -Category 'data' `
        -Scope Shared `
        -RelativePath 'scripts\seed-demo\build-demo-registry.ps1'
    $frontendRoot = Join-Path $repositoryRoot 'frontend'
    Invoke-RequiredCommand `
        -Id 'frontend.install' `
        -Category 'frontend' `
        -Scope Shared `
        -FilePath 'npm' `
        -Arguments @('ci') `
        -WorkingDirectory $frontendRoot
    Invoke-RequiredCommand `
        -Id 'frontend.typecheck' `
        -Category 'frontend' `
        -Scope Shared `
        -FilePath 'npm' `
        -Arguments @('run', 'typecheck') `
        -WorkingDirectory $frontendRoot
    Invoke-RequiredCommand `
        -Id 'frontend.test' `
        -Category 'frontend' `
        -Scope Shared `
        -FilePath 'npm' `
        -Arguments @('test', '--', '--run') `
        -WorkingDirectory $frontendRoot
    Invoke-RequiredCommand `
        -Id 'frontend.build' `
        -Category 'frontend' `
        -Scope Shared `
        -FilePath 'npm' `
        -Arguments @('run', 'build') `
        -WorkingDirectory $frontendRoot

    if ($Target -in @('Local', 'All')) {
        Invoke-RequiredScript `
            -Id 'local.compose' `
            -Category 'operations' `
            -Scope Local `
            -RelativePath 'scripts\verify\verify-local-compose.ps1' `
            -Arguments @('-EnvFile', $EnvFile)
        Invoke-RequiredScript `
            -Id 'local.live-contracts' `
            -Category 'contracts' `
            -Scope Local `
            -RelativePath 'tests\contract\verify-live-contracts.ps1' `
            -Arguments @(
                '-BaseUrl', 'http://127.0.0.1',
                '-EnvFile', $EnvFile,
                '-OutputPath', (Get-RunEvidencePath -Name 'local-live-contracts.json')
            )
        Invoke-RequiredScript `
            -Id 'local.closed-loop' `
            -Category 'e2e' `
            -Scope Local `
            -RelativePath 'tests\e2e\closed-loop.ps1' `
            -Arguments @(
                '-BaseUrl', 'http://127.0.0.1',
                '-EnvFile', $EnvFile,
                '-Scope', 'Local',
                '-OutputPath', (Get-RunEvidencePath -Name 'local-closed-loop.json')
            )
        Invoke-RequiredScript `
            -Id 'local.deepseek-tool-call' `
            -Category 'ai' `
            -Scope Local `
            -RelativePath 'tests\e2e\deepseek-tool-call.ps1' `
            -Arguments @(
                '-BaseUrl', 'http://127.0.0.1',
                '-EnvFile', $EnvFile,
                '-OutputPath', (Get-RunEvidencePath -Name 'local-deepseek-tool-call.json')
            )
        Invoke-RequiredScript `
            -Id 'local.resilience' `
            -Category 'resilience' `
            -Scope Local `
            -RelativePath 'tests\resilience\verify-resilience.ps1' `
            -Arguments @(
                '-BaseUrl', 'http://127.0.0.1',
                '-EnvFile', $EnvFile,
                '-OutputPath', (Get-RunEvidencePath -Name 'local-resilience.json')
            )
        Invoke-RequiredScript `
            -Id 'local.performance' `
            -Category 'performance' `
            -Scope Local `
            -RelativePath 'tests\performance\run-k6.ps1' `
            -Arguments @(
                '-BaseUrl', 'http://127.0.0.1',
                '-EnvFile', $EnvFile,
                '-Scope', 'Local',
                '-OutputPath', (Get-RunEvidencePath -Name 'local-performance.json')
            )
        Invoke-RequiredScript `
            -Id 'local.restart-recovery' `
            -Category 'operations' `
            -Scope Local `
            -RelativePath 'tests\resilience\verify-restart-recovery.ps1' `
            -Arguments @(
                '-BaseUrl', 'http://127.0.0.1',
                '-EnvFile', $EnvFile,
                '-OutputPath', (Get-RunEvidencePath -Name 'local-restart-recovery.json')
            )
        Invoke-RequiredScript `
            -Id 'local.model-rollback' `
            -Category 'operations' `
            -Scope Local `
            -RelativePath 'tests\resilience\verify-model-rollback.ps1' `
            -Arguments @(
                '-BaseUrl', 'http://127.0.0.1',
                '-EnvFile', $EnvFile,
                '-OutputPath', (Get-RunEvidencePath -Name 'local-model-rollback.json')
            )
        Invoke-RequiredScript `
            -Id 'local.runtime-evidence' `
            -Category 'evidence' `
            -Scope Local `
            -RelativePath 'scripts\verify\collect-local-runtime-evidence.ps1' `
            -Arguments @('-EnvFile', $EnvFile, '-OutputPath', (Join-Path $evidenceDirectory 'local-runtime.json'))
    }

    if ($Target -in @('Server', 'All')) {
        $localFailures = @($manifest.gates | Where-Object {
                $_.scope -in @('Shared', 'Local') -and $_.status -ne 'PASSED'
            }).Count
        if ($Target -eq 'All' -and $localFailures -gt 0) {
            Invoke-FailedGate `
                -Manifest $manifest `
                -RepositoryRoot $repositoryRoot `
                -EvidenceDirectory $evidenceDirectory `
                -Id 'server.deploy' `
                -Category 'deployment' `
                -Scope Server `
                -Command 'infra/scripts/deploy/deploy.ps1' `
                -Message 'Server deployment requires all shared and local gates to pass.' `
                -ExitCode 3 | Out-Null
        }
        else {
            $imageTag = "verify-$($codeTreeSha256.Substring(0, 12))"
            Invoke-RequiredScript `
                -Id 'server.deploy' `
                -Category 'deployment' `
                -Scope Server `
                -RelativePath 'infra\scripts\deploy\deploy.ps1' `
                -Arguments @(
                    '-ImageTag', $imageTag,
                    '-Server', $Server,
                    '-SshUser', $SshUser,
                    '-IdentityFile', $IdentityFile,
                    '-EnvFile', $EnvFile,
                    '-Transport', 'Offline'
                )
        }
        Invoke-RequiredScript `
            -Id 'server.boundary' `
            -Category 'deployment' `
            -Scope Server `
            -RelativePath 'scripts\verify\verify-server-boundary.ps1' `
            -Arguments @(
                '-Server', $Server,
                '-SshUser', $SshUser,
                '-IdentityFile', $IdentityFile,
                '-OutputPath', (Get-RunEvidencePath -Name 'server-boundary.json')
            )
        Invoke-RequiredScript `
            -Id 'server.closed-loop' `
            -Category 'e2e' `
            -Scope Server `
            -RelativePath 'tests\e2e\closed-loop.ps1' `
            -Arguments @(
                '-BaseUrl', $ServerBaseUrl,
                '-EnvFile', $EnvFile,
                '-Scope', 'Server',
                '-Server', $Server,
                '-SshUser', $SshUser,
                '-IdentityFile', $IdentityFile,
                '-OutputPath', (Get-RunEvidencePath -Name 'server-closed-loop.json')
            )
        Invoke-RequiredScript `
            -Id 'server.live-contracts' `
            -Category 'contracts' `
            -Scope Server `
            -RelativePath 'tests\contract\verify-live-contracts.ps1' `
            -Arguments @(
                '-BaseUrl', $ServerBaseUrl,
                '-EnvFile', $EnvFile,
                '-OutputPath', (Get-RunEvidencePath -Name 'server-live-contracts.json')
            )
        Invoke-RequiredScript `
            -Id 'server.performance' `
            -Category 'performance' `
            -Scope Server `
            -RelativePath 'tests\performance\run-k6.ps1' `
            -Arguments @(
                '-BaseUrl', $ServerBaseUrl,
                '-EnvFile', $EnvFile,
                '-Scope', 'Server',
                '-Server', $Server,
                '-SshUser', $SshUser,
                '-IdentityFile', $IdentityFile,
                '-OutputPath', (Get-RunEvidencePath -Name 'server-performance.json')
            )
        Invoke-RequiredScript `
            -Id 'server.restart-recovery' `
            -Category 'operations' `
            -Scope Server `
            -RelativePath 'scripts\verify\verify-server-restart.ps1' `
            -Arguments @(
                '-Server', $Server,
                '-SshUser', $SshUser,
                '-IdentityFile', $IdentityFile,
                '-BaseUrl', $ServerBaseUrl,
                '-EnvFile', $EnvFile,
                '-ClosedLoopEvidence', (Get-RunEvidencePath -Name 'server-closed-loop.json'),
                '-OutputPath', (Get-RunEvidencePath -Name 'server-restart-recovery.json')
            )
        Invoke-RequiredScript `
            -Id 'server.rollback' `
            -Category 'operations' `
            -Scope Server `
            -RelativePath 'scripts\verify\verify-server-rollback.ps1' `
            -Arguments @(
                '-Server', $Server,
                '-SshUser', $SshUser,
                '-IdentityFile', $IdentityFile,
                '-BaseUrl', $ServerBaseUrl,
                '-EnvFile', $EnvFile,
                '-ClosedLoopEvidence', (Get-RunEvidencePath -Name 'server-closed-loop.json'),
                '-OutputPath', (Get-RunEvidencePath -Name 'server-rollback.json')
            )
        Invoke-RequiredScript `
            -Id 'server.runtime-evidence' `
            -Category 'evidence' `
            -Scope Server `
            -RelativePath 'scripts\verify\collect-server-runtime-evidence.ps1' `
            -Arguments @(
                '-Server', $Server,
                '-SshUser', $SshUser,
                '-IdentityFile', $IdentityFile,
                '-OutputPath', (Join-Path $evidenceDirectory 'server-runtime.json')
            )
    }
}
catch {
    Add-OpenItem `
        -Manifest $manifest `
        -Id 'orchestrator.unhandled-error' `
        -Scope Shared `
        -Message $_.Exception.Message `
        -Evidence @('scripts/verify-delivery.ps1')
}
finally {
    Collect-StaticEvidence
    Import-RuntimeEvidence `
        -Scope Local `
        -Path (Join-Path $evidenceDirectory 'local-runtime.json')
    Import-RuntimeEvidence `
        -Scope Server `
        -Path (Join-Path $evidenceDirectory 'server-runtime.json')
    Collect-ResultEvidence
    Invoke-CheckGate `
        -Manifest $manifest `
        -RepositoryRoot $repositoryRoot `
        -EvidenceDirectory $evidenceDirectory `
        -Id 'delivery.evidence' `
        -Category 'evidence' `
        -Scope Shared `
        -Command 'validate structured delivery evidence' `
        -Action {
            if ($null -eq $manifest.data.sourceLock -or
                $null -eq $manifest.data.processingManifest -or
                @($manifest.data.datasets).Count -ne 3) {
                throw 'Structured evidence does not bind all required data manifests and versions.'
            }
            if ($null -eq $manifest.models.knowledgeFreeze -or
                $null -eq $manifest.models.knowledgeTestMetrics -or
                $null -eq $manifest.models.riskFreeze -or
                $null -eq $manifest.models.riskTestMetrics -or
                @($manifest.models.versions | Where-Object task -eq 'KNOWLEDGE').Count -ne 4 -or
                @($manifest.models.versions | Where-Object task -eq 'RISK').Count -ne 3 -or
                @($manifest.models.testEvaluations).Count -ne 2 -or
                @($manifest.models.artifacts).Count -eq 0) {
                throw 'Structured evidence does not bind all model freezes, cohorts, metrics, and artifacts.'
            }
            if ($manifest.local.requested -and
                @($manifest.results.local.Values | Where-Object { $null -eq $_ }).Count -ne 0) {
                throw 'One or more requested local result reports are missing.'
            }
            if ($manifest.server.requested -and
                @($manifest.results.server.Values | Where-Object { $null -eq $_ }).Count -ne 0) {
                throw 'One or more requested server result reports are missing.'
            }
            'data, model, and requested runtime evidence is complete'
        } | Out-Null
    if ($Target -eq 'All') {
        Invoke-CheckGate `
            -Manifest $manifest `
            -RepositoryRoot $repositoryRoot `
            -EvidenceDirectory $evidenceDirectory `
            -Id 'server.image-digest-parity' `
            -Category 'deployment' `
            -Scope Server `
            -Command 'compare local and server image ids' `
            -Action {
                $localIds = @(
                    $manifest.images |
                        Where-Object scope -eq 'Local' |
                        ForEach-Object id |
                        Sort-Object
                )
                $serverIds = @(
                    $manifest.images |
                        Where-Object scope -eq 'Server' |
                        ForEach-Object id |
                        Sort-Object
                )
                if ($localIds.Count -ne 6 -or $serverIds.Count -ne 6 -or
                    [string]::Join(',', $localIds) -ne [string]::Join(',', $serverIds)) {
                    throw 'Local and server image IDs are incomplete or differ.'
                }
                'six local and server image IDs match'
            } | Out-Null
    }
    Update-AcceptanceStatus -Manifest $manifest
    $writtenManifest = Write-AcceptanceManifest `
        -Manifest $manifest `
        -Path $manifestPath `
        -SchemaPath $schemaPath
    Write-Output "Acceptance manifest: $writtenManifest"
    Write-Output "Acceptance status: $($manifest.status)"
}

if ($manifest.status -ne 'PASSED') {
    exit 1
}
exit 0
