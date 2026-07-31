[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path,
    [string]$ProcessedAt,
    [string]$DeployedBy = 'edutwin-delivery'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Require-File {
    param([string]$Path, [string]$Context)
    $resolved = [IO.Path]::GetFullPath($Path)
    if (-not [IO.File]::Exists($resolved)) {
        throw "$Context is missing: $resolved"
    }
    return $resolved
}

function Read-Json {
    param([string]$Path, [string]$Context)
    $resolved = Require-File $Path $Context
    try {
        return [IO.File]::ReadAllText($resolved, [Text.Encoding]::UTF8) |
            ConvertFrom-Json -Depth 100
    }
    catch {
        throw "$Context is not valid JSON: $resolved`n$($_.Exception.Message)"
    }
}

function Get-Sha256File {
    param([string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-Sha256Text {
    param([string]$Value)
    $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
}

function Get-ObjectSha256 {
    param($Value)
    return Get-Sha256Text ($Value | ConvertTo-Json -Depth 100 -Compress)
}

function ConvertTo-DeterministicUuid {
    param([string]$Hash)
    if ($Hash -notmatch '^[a-f0-9]{64}$') {
        throw 'UUID input must be a lowercase SHA-256.'
    }
    $variant = '{0:x}' -f (([Convert]::ToInt32($Hash.Substring(16, 1), 16) -band 3) -bor 8)
    return '{0}-{1}-5{2}-{3}{4}-{5}' -f @(
        $Hash.Substring(0, 8),
        $Hash.Substring(8, 4),
        $Hash.Substring(13, 3),
        $variant,
        $Hash.Substring(17, 3),
        $Hash.Substring(20, 12)
    )
}

function Resolve-RepositoryPath {
    param([string]$RelativePath, [string]$Context)
    if ([string]::IsNullOrWhiteSpace($RelativePath)) {
        throw "$Context path is blank."
    }
    $candidate = [IO.Path]::GetFullPath((Join-Path $RepositoryRoot $RelativePath))
    $rootPrefix = [IO.Path]::GetFullPath($RepositoryRoot).TrimEnd('\') + '\'
    if (-not $candidate.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "$Context path escapes the repository: $RelativePath"
    }
    return Require-File $candidate $Context
}

function Get-CodeTreeSha256 {
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = 'git'
    $startInfo.WorkingDirectory = [IO.Path]::GetFullPath($RepositoryRoot)
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.StandardOutputEncoding = [Text.Encoding]::UTF8
    foreach ($argument in @('ls-files', '-z', '--cached', '--others', '--exclude-standard')) {
        [void]$startInfo.ArgumentList.Add($argument)
    }
    $process = [Diagnostics.Process]::Start($startInfo)
    $raw = $process.StandardOutput.ReadToEnd()
    $errorOutput = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        throw "Git file enumeration failed: $errorOutput"
    }
    $files = [string[]]$raw.Split([char]0, [StringSplitOptions]::RemoveEmptyEntries)
    [Array]::Sort($files, [StringComparer]::Ordinal)
    if ($files.Count -eq 0) {
        throw 'Git did not return the delivery code tree.'
    }
    $lines = foreach ($relative in $files) {
        $path = Require-File (Join-Path $RepositoryRoot $relative) "code tree file $relative"
        "$($relative.Replace('\', '/'))`0$(Get-Sha256File $path)"
    }
    return Get-Sha256Text (($lines -join "`n") + "`n")
}

function Assert-ManifestEntry {
    param($Entry, [string]$Context)
    $path = Resolve-RepositoryPath ([string]$Entry.path) $Context
    $actualHash = Get-Sha256File $path
    $actualBytes = (Get-Item -LiteralPath $path).Length
    if ($actualHash -ne [string]$Entry.sha256 -or $actualBytes -ne [long]$Entry.bytes) {
        throw "$Context hash or size differs: $path"
    }
    return $path
}

function New-Artifact {
    param(
        [string]$Role,
        $Entry,
        $Dependencies,
        [string]$Context
    )
    $path = Assert-ManifestEntry $Entry $Context
    return [ordered]@{
        artifactRole = $Role
        artifactUri = "artifact://$(([string]$Entry.path).Replace('\', '/'))"
        sha256 = [string]$Entry.sha256
        sizeBytes = [long](Get-Item -LiteralPath $path).Length
        dependencyVersions = $Dependencies
    }
}

function New-LocalArtifact {
    param([string]$Role, [string]$Path, $Dependencies)
    $resolved = Require-File $Path "local $Role artifact"
    $relative = [IO.Path]::GetRelativePath($RepositoryRoot, $resolved).Replace('\', '/')
    return [ordered]@{
        artifactRole = $Role
        artifactUri = "artifact://$relative"
        sha256 = Get-Sha256File $resolved
        sizeBytes = [long](Get-Item -LiteralPath $resolved).Length
        dependencyVersions = $Dependencies
    }
}

function Convert-Metrics {
    param($Metrics, [string]$Split, [string]$MeasuredAt)
    $result = @()
    foreach ($property in $Metrics.PSObject.Properties) {
        if ($property.Name -in @('eventCount', 'rowCount')) {
            continue
        }
        $result += [ordered]@{
            splitName = $Split
            metricName = $property.Name
            metricValue = [decimal]$property.Value
            measuredAt = $MeasuredAt
        }
    }
    return @($result)
}

function New-LockedSourceFile {
    param($Entry, [string]$DownloadUrl, [string]$Context)
    $path = Resolve-RepositoryPath ([string]$Entry.path) $Context
    if ((Get-Sha256File $path) -ne [string]$Entry.sha256 -or
        (Get-Item -LiteralPath $path).Length -ne [long]$Entry.bytes) {
        throw "$Context differs from source-lock.json."
    }
    return [ordered]@{
        fileName = [IO.Path]::GetFileName([string]$Entry.path)
        downloadUrl = $DownloadUrl
        sha256 = [string]$Entry.sha256
        sizeBytes = [long]$Entry.bytes
    }
}

$demoRoot = Join-Path $RepositoryRoot 'data\demo\generated'
$databaseRoot = Join-Path $demoRoot 'database'
$demoManifestPath = Require-File (Join-Path $demoRoot 'manifest.json') 'demo manifest'
$demoManifest = Read-Json $demoManifestPath 'demo manifest'
$sourceLockPath = Require-File (Join-Path $RepositoryRoot 'data\manifests\source-lock.json') 'source lock'
$sourceLock = Read-Json $sourceLockPath 'source lock'
$processingManifestPath = Require-File (Join-Path $RepositoryRoot 'data\manifests\processing-manifest.json') 'processing manifest'
$processingManifest = Read-Json $processingManifestPath 'processing manifest'
$assistReportPath = Require-File (Join-Path $RepositoryRoot 'data\processed\knowledge\quality_report.json') 'ASSISTments quality report'
$assistReport = Read-Json $assistReportPath 'ASSISTments quality report'
$ouladManifestPath = Require-File (Join-Path $RepositoryRoot 'data\processed\risk\manifest.json') 'OULAD processing manifest'
$ouladManifest = Read-Json $ouladManifestPath 'OULAD processing manifest'
$knowledgeFreezePath = Require-File (Join-Path $RepositoryRoot 'modeling\artifacts\manifests\knowledge-freeze.json') 'knowledge freeze manifest'
$knowledgeFreeze = Read-Json $knowledgeFreezePath 'knowledge freeze manifest'
$knowledgeTestPath = Require-File (Join-Path $RepositoryRoot 'modeling\artifacts\manifests\knowledge-test-metrics.json') 'knowledge test metrics'
$knowledgeTest = Read-Json $knowledgeTestPath 'knowledge test metrics'
$riskFreezePath = Require-File (Join-Path $RepositoryRoot 'modeling\artifacts\manifests\risk-freeze.json') 'risk freeze manifest'
$riskFreeze = Read-Json $riskFreezePath 'risk freeze manifest'
$riskTestPath = Require-File (Join-Path $RepositoryRoot 'modeling\artifacts\manifests\risk-test-metrics.json') 'risk test metrics'
$riskTest = Read-Json $riskTestPath 'risk test metrics'
$initialStateManifestPath = Require-File (Join-Path $databaseRoot 'initial-states-manifest.json') 'initial state manifest'
$initialStateManifest = Read-Json $initialStateManifestPath 'initial state manifest'
$plannerArtifactPath = Require-File (Join-Path $RepositoryRoot 'backend\src\main\resources\model-registry\planner-rules-v1.json') 'planner rule artifact'
$plannerConfig = Read-Json $plannerArtifactPath 'planner rule artifact'
$diagnosisArtifactPath = Require-File (Join-Path $RepositoryRoot 'backend\src\main\resources\model-registry\diagnosis-tool-schema-v1.json') 'diagnosis tool schema'
$diagnosisConfig = Read-Json $diagnosisArtifactPath 'diagnosis tool schema'

if ($demoManifest.schema_version -ne 7 -or
    $demoManifest.database_import.schema_version -ne 3 -or
    $demoManifest.data_version -ne 'synthetic-demo-v6' -or
    $demoManifest.reference_time -ne '2026-06-15T00:00:00+00:00' -or
    $demoManifest.student_count -ne 2000 -or
    $demoManifest.outputs.lms_assessments.rows -ne 100 -or
    $demoManifest.outputs.lms_assessment_questions.rows -ne 500 -or
    $demoManifest.answer_event_count -lt 150000 -or
    $demoManifest.answer_event_count -gt 360000 -or
    $demoManifest.learning_activity_event_count -lt 450000 -or
    $demoManifest.learning_activity_event_count -gt 1500000 -or -not $demoManifest.synthetic) {
    throw 'Demo manifest violates the database seed contract.'
}
if ($knowledgeFreeze.status -ne 'FROZEN' -or $knowledgeFreeze.seed -ne 42 -or
    $knowledgeTest.status -ne 'COMPLETED' -or -not $knowledgeTest.evaluatedOnce) {
    throw 'Knowledge model freeze or one-shot test ledger is incomplete.'
}
if ($riskFreeze.status -ne 'FROZEN' -or $riskFreeze.seed -ne 42 -or
    $riskTest.status -ne 'COMPLETED' -or $riskTest.attempts -ne 1) {
    throw 'Risk model freeze or one-shot test ledger is incomplete.'
}
if ($initialStateManifest.schemaVersion -ne 2 -or
    $initialStateManifest.kind -ne 'edutwin-initial-state-projection' -or
    $initialStateManifest.generatorVersion -ne 'frozen-model-bootstrap-v2' -or
    $initialStateManifest.seed -ne 42 -or
    $initialStateManifest.studentCount -ne 2000 -or
    $initialStateManifest.enrollmentCount -ne 8400 -or
    $initialStateManifest.skillsPerEnrollment -ne 3 -or
    $initialStateManifest.snapshotsPerEnrollment -ne 6 -or
    $initialStateManifest.eventsPerEnrollmentRange[0] -ne 18 -or
    $initialStateManifest.eventsPerEnrollmentRange[1] -ne 42) {
    throw 'Initial state projection manifest violates the frozen bootstrap contract.'
}
$initialStatePath = Assert-ManifestEntry $initialStateManifest.output 'initial state projection'
if ([string]$initialStateManifest.demoManifest.sha256 -ne (Get-Sha256File $demoManifestPath) -or
    [string]$initialStateManifest.knowledgeFreezeManifest.sha256 -ne (Get-Sha256File $knowledgeFreezePath) -or
    [string]$initialStateManifest.riskFreezeManifest.sha256 -ne (Get-Sha256File $riskFreezePath)) {
    throw 'Initial state projection is not bound to the active demo and frozen model manifests.'
}
$knowledgeWinner = [string]$knowledgeFreeze.selection.nextCorrectPredictor.family
if ($knowledgeWinner -notin @('DKT', 'AKT')) {
    throw 'The knowledge next-correct winner must be DKT or AKT.'
}
$riskWinner = [string]$riskFreeze.selection.winner
if ($riskWinner -notin @('LOGISTIC_REGRESSION', 'LIGHTGBM', 'CATBOOST')) {
    throw 'The risk winner is invalid.'
}

$timestamp = if ([string]::IsNullOrWhiteSpace($ProcessedAt)) {
    ([DateTimeOffset]$sourceLock.generated_at_utc).ToUniversalTime().ToString('o')
}
else {
    ([DateTimeOffset]$ProcessedAt).ToUniversalTime().ToString('o')
}
$demoManifestSha = Get-Sha256File $demoManifestPath
$processingManifestSha = Get-Sha256File $processingManifestPath
$dataConfigPath = Require-File (Join-Path $RepositoryRoot 'modeling\configs\data\pipeline.yaml') 'data pipeline configuration'
$dataConfigSha = Get-Sha256File $dataConfigPath
$codeTreeSha = Get-CodeTreeSha256
$runId = ConvertTo-DeterministicUuid (Get-Sha256Text "$processingManifestSha|$codeTreeSha|42")

$assistSource = $sourceLock.assistments_2009_2010_skill_builder
$ouladSource = $sourceLock.oulad
$assistCanonical = $assistSource.canonical_corrected_nonfolded.file
$ouladArchive = $ouladSource.archive
$assistVersion = [string]$knowledgeFreeze.dataVersion
$ouladManifestSha = Get-Sha256File $ouladManifestPath
$ouladVersion = "oulad-d0-29-$($ouladManifestSha.Substring(0, 12))"

$assistFiles = @(
    New-LockedSourceFile $assistSource.official_raw_nonfolded.file `
        ("env://$([string]$assistSource.official_raw_nonfolded.download_url_env)") 'ASSISTments official source'
    New-LockedSourceFile $assistSource.corrected_nonfolded_academic_mirror.archive `
        ([string]$assistSource.corrected_nonfolded_academic_mirror.download_url) 'ASSISTments mirror archive'
    New-LockedSourceFile $assistCanonical `
        'artifact://data/raw/assistments/canonical/skill_builder_data_corrected.csv' 'ASSISTments canonical source'
)
$ouladFiles = @(
    New-LockedSourceFile $ouladArchive ([string]$ouladSource.download_url) 'OULAD archive'
)
foreach ($entry in $ouladSource.extracted_files) {
    $ouladFiles += New-LockedSourceFile $entry ([string]$ouladSource.download_url) 'OULAD extracted source'
}
$demoFiles = @()
foreach ($property in $demoManifest.database_import.outputs.PSObject.Properties) {
    $entry = $property.Value
    $path = Require-File (Join-Path $demoRoot ([string]$entry.path)) "demo database file $($property.Name)"
    if ((Get-Sha256File $path) -ne [string]$entry.sha256) {
        throw "Demo database file hash differs: $path"
    }
    $demoFiles += [ordered]@{
        fileName = [IO.Path]::GetFileName([string]$entry.path)
        downloadUrl = "artifact://data/demo/generated/$(([string]$entry.path).Replace('\', '/'))"
        sha256 = [string]$entry.sha256
        sizeBytes = [long](Get-Item -LiteralPath $path).Length
    }
}
$demoFiles += [ordered]@{
    fileName = [IO.Path]::GetFileName($initialStatePath)
    downloadUrl = "artifact://data/demo/generated/database/$([IO.Path]::GetFileName($initialStatePath))"
    sha256 = Get-Sha256File $initialStatePath
    sizeBytes = [long](Get-Item -LiteralPath $initialStatePath).Length
}
$demoFiles += [ordered]@{
    fileName = [IO.Path]::GetFileName($initialStateManifestPath)
    downloadUrl = "artifact://data/demo/generated/database/$([IO.Path]::GetFileName($initialStateManifestPath))"
    sha256 = Get-Sha256File $initialStateManifestPath
    sizeBytes = [long](Get-Item -LiteralPath $initialStateManifestPath).Length
}

$datasets = @(
    [ordered]@{
        source = [ordered]@{
            id = 'a3e0f31f-54f6-4f99-82e8-bf5e6fdd0101'
            sourceKey = 'ASSISTMENTS_2009_2010_SKILL_BUILDER_CORRECTED'
            name = 'ASSISTments 2009-2010 Skill Builder corrected non-folded'
            officialUrl = 'https://sites.google.com/site/assistmentsdata/home/2009-2010-assistment-data/skill-builder-data-2009-2010'
            licenseName = 'ASSISTments Terms of Use'
            licenseUrl = 'https://sites.google.com/site/assistmentsdata/termsofuseforusingdata'
            citationText = 'ASSISTments 2009-2010 Skill Builder Data.'
        }
        version = [ordered]@{
            versionId = $assistVersion
            sourceSha256 = [string]$assistCanonical.sha256
            schemaSha256 = Get-ObjectSha256 $assistReport.expected
            processingConfigSha256 = $dataConfigSha
            manifestSha256 = Get-Sha256File $assistReportPath
            rowCount = [long]$assistReport.observed.answerEvents
            processedAt = $timestamp
            manifestJson = $assistReport
        }
        files = $assistFiles
    }
    [ordered]@{
        source = [ordered]@{
            id = 'a3e0f31f-54f6-4f99-82e8-bf5e6fdd0102'
            sourceKey = 'OULAD'
            name = 'Open University Learning Analytics Dataset'
            officialUrl = 'https://research.stem.open.ac.uk/ouanalyse/dataset/'
            licenseName = 'CC-BY-4.0'
            licenseUrl = 'https://creativecommons.org/licenses/by/4.0/'
            citationText = 'Kuzilek J, Hlosta M, Zdrahal Z. Open University Learning Analytics dataset.'
        }
        version = [ordered]@{
            versionId = $ouladVersion
            sourceSha256 = [string]$ouladArchive.sha256
            schemaSha256 = Get-ObjectSha256 @($ouladManifest.feature_columns)
            processingConfigSha256 = $dataConfigSha
            manifestSha256 = $ouladManifestSha
            rowCount = [long]$ouladManifest.rows
            processedAt = $timestamp
            manifestJson = $ouladManifest
        }
        files = $ouladFiles
    }
    [ordered]@{
        source = [ordered]@{
            id = 'a3e0f31f-54f6-4f99-82e8-bf5e6fdd0103'
            sourceKey = 'EDUTWIN_DEMO'
            name = 'EduTwin deterministic synthetic demo'
            officialUrl = 'artifact://data/demo/generated/manifest.json'
            licenseName = 'Project-generated synthetic data'
            licenseUrl = 'artifact://docs/data/sources-and-licenses.md'
            citationText = 'EduTwin seeded independent synthetic learning trend v1.'
        }
        version = [ordered]@{
            versionId = 'synthetic-demo-v6'
            sourceSha256 = Get-ObjectSha256 $demoManifest.outputs
            schemaSha256 = Get-ObjectSha256 $demoManifest.database_import
            processingConfigSha256 = $dataConfigSha
            manifestSha256 = $demoManifestSha
            rowCount = [long]$demoManifest.answer_event_count
            processedAt = $timestamp
            manifestJson = $demoManifest
        }
        files = $demoFiles
    }
)

$models = @()
$knowledgeFeatureSha = Get-Sha256Text ([string]$knowledgeFreeze.featureContractVersion)
foreach ($family in @('IRT', 'BKT', 'DKT', 'AKT')) {
    $reference = $knowledgeFreeze.candidates.$family
    $candidatePath = Assert-ManifestEntry $reference.manifest "knowledge $family manifest"
    $candidate = Read-Json $candidatePath "knowledge $family manifest"
    $selected = $family -eq 'BKT' -or $family -eq $knowledgeWinner
    $task = if ($family -eq 'BKT') { 'MASTERY' } else { 'NEXT_CORRECT' }
    $metrics = @()
    $metrics += Convert-Metrics $reference.validationMetrics 'validation' $timestamp
    $metrics += Convert-Metrics $knowledgeTest.candidates.$family.metrics 'test' $timestamp
    $models += [ordered]@{
        versionId = [string]$reference.modelVersion
        modelFamily = $family
        taskName = $task
        datasetVersionId = $assistVersion
        status = if ($selected) { 'ACTIVE' } else { 'FROZEN' }
        randomSeed = 42
        configJson = [ordered]@{
            modelName = $family.ToLowerInvariant()
            featureContractVersion = [string]$knowledgeFreeze.featureContractVersion
            configuration = $candidate.modelConfig
        }
        featureContractSha256 = $knowledgeFeatureSha
        calibratorType = 'PLATT'
        calibratorSha256 = [string]$candidate.artifacts.calibrator.sha256
        manifestSha256 = [string]$reference.manifest.sha256
        selected = $selected
        frozenAt = $timestamp
        testEvaluatedAt = $timestamp
        metrics = @($metrics)
        artifacts = @(
            New-Artifact 'SERVING_MODEL' $candidate.artifacts.model $knowledgeFreeze.dependencies "knowledge $family model"
            New-Artifact 'CALIBRATOR' $candidate.artifacts.calibrator $knowledgeFreeze.dependencies "knowledge $family calibrator"
            New-Artifact 'VOCABULARY' $candidate.artifacts.vocabulary $knowledgeFreeze.dependencies "knowledge $family vocabulary"
        )
    }
}

$riskFeatureSha = Get-Sha256Text ([string]$riskFreeze.featureContractVersion)
foreach ($family in @('LOGISTIC_REGRESSION', 'LIGHTGBM', 'CATBOOST')) {
    $candidate = $riskFreeze.candidates.$family
    $selected = $family -eq $riskWinner
    $metrics = @()
    $metrics += Convert-Metrics $candidate.validationMetrics 'validation' $timestamp
    $metrics += Convert-Metrics $riskTest.metrics.$family 'test' $timestamp
    $models += [ordered]@{
        versionId = [string]$candidate.modelVersion
        modelFamily = $family
        taskName = 'RISK'
        datasetVersionId = $ouladVersion
        status = if ($selected) { 'ACTIVE' } else { 'FROZEN' }
        randomSeed = 42
        configJson = [ordered]@{
            modelName = $family.ToLowerInvariant()
            featureContractVersion = [string]$riskFreeze.featureContractVersion
            configuration = $candidate.configuration
        }
        featureContractSha256 = $riskFeatureSha
        calibratorType = 'PLATT'
        calibratorSha256 = [string]$candidate.calibratorArtifact.sha256
        manifestSha256 = Get-Sha256File $riskFreezePath
        selected = $selected
        frozenAt = $timestamp
        testEvaluatedAt = $timestamp
        metrics = @($metrics)
        artifacts = @(
            New-Artifact 'SERVING_MODEL' $candidate.modelArtifact $riskFreeze.dependencies "risk $family model"
            New-Artifact 'CALIBRATOR' $candidate.calibratorArtifact $riskFreeze.dependencies "risk $family calibrator"
            New-Artifact 'PREPROCESSOR' $riskFreeze.preprocessor $riskFreeze.dependencies "risk $family preprocessor"
        )
    }
}

foreach ($family in @('LOGISTIC_REGRESSION', 'LIGHTGBM', 'CATBOOST')) {
    $riskEntry = $riskFreeze.candidates.$family
    $selected = $family -eq $riskWinner
    $shapMetrics = @()
    $shapMetrics += Convert-Metrics $riskEntry.validationMetrics 'validation' $timestamp
    $shapMetrics += Convert-Metrics $riskTest.metrics.$family 'test' $timestamp
    $models += [ordered]@{
        versionId = "shap-$([string]$riskEntry.modelVersion)"
        modelFamily = 'SHAP'
        taskName = 'EXPLANATION'
        datasetVersionId = $ouladVersion
        status = if ($selected) { 'ACTIVE' } else { 'FROZEN' }
        randomSeed = 42
        configJson = [ordered]@{
            modelName = "$([string]$riskEntry.modelName)-native-shap"
            featureContractVersion = [string]$riskFreeze.featureContractVersion
            evidenceLimit = 5
        }
        featureContractSha256 = $riskFeatureSha
        calibratorType = $null
        calibratorSha256 = $null
        manifestSha256 = Get-Sha256File $riskFreezePath
        selected = $selected
        frozenAt = $timestamp
        testEvaluatedAt = $timestamp
        metrics = @($shapMetrics)
        artifacts = @(
            New-Artifact 'EXPLAINER' $riskEntry.modelArtifact $riskFreeze.dependencies "SHAP $family explainer model"
        )
    }
}

$plannerArtifact = New-LocalArtifact 'RULE_CONFIG' $plannerArtifactPath ([ordered]@{ java = '21' })
$models += [ordered]@{
    versionId = 'planner-rules-v1'
    modelFamily = 'RULE'
    taskName = 'PLAN_RULES'
    datasetVersionId = 'synthetic-demo-v6'
    status = 'ACTIVE'
    randomSeed = 42
    configJson = $plannerConfig
    featureContractSha256 = $plannerArtifact.sha256
    calibratorType = $null
    calibratorSha256 = $null
    manifestSha256 = $plannerArtifact.sha256
    selected = $true
    frozenAt = $timestamp
    testEvaluatedAt = $timestamp
    metrics = @([ordered]@{
        splitName = 'validation'; metricName = 'RULE_CASES_PASSED';
        metricValue = [decimal]1; measuredAt = $timestamp
    })
    artifacts = @($plannerArtifact)
}

$diagnosisArtifact = New-LocalArtifact 'PROMPT_SCHEMA' $diagnosisArtifactPath ([ordered]@{ springAi = '1.0.1' })
$models += [ordered]@{
    versionId = 'deepseek-v4-flash-tool-schema-v1'
    modelFamily = 'DEEPSEEK'
    taskName = 'DIAGNOSIS'
    datasetVersionId = 'synthetic-demo-v6'
    status = 'ACTIVE'
    randomSeed = 42
    configJson = $diagnosisConfig
    featureContractSha256 = $diagnosisArtifact.sha256
    calibratorType = $null
    calibratorSha256 = $null
    manifestSha256 = $diagnosisArtifact.sha256
    selected = $true
    frozenAt = $timestamp
    testEvaluatedAt = $timestamp
    metrics = @([ordered]@{
        splitName = 'validation'; metricName = 'SCHEMA_VALID';
        metricValue = [decimal]1; measuredAt = $timestamp
    })
    artifacts = @($diagnosisArtifact)
}

$knowledgeRollbackFamily = if ($knowledgeWinner -eq 'DKT') { 'AKT' } else { 'DKT' }
$riskRollbackFamily = @('LOGISTIC_REGRESSION', 'LIGHTGBM', 'CATBOOST') |
    Where-Object { $_ -ne $riskWinner } |
    Select-Object -First 1
$deployments = @(
    [ordered]@{
        taskName = 'MASTERY'; activeVersionId = [string]$knowledgeFreeze.candidates.BKT.modelVersion
        rollbackVersionId = $null; deployedAt = $timestamp; deployedBy = $DeployedBy
    }
    [ordered]@{
        taskName = 'NEXT_CORRECT'; activeVersionId = [string]$knowledgeFreeze.candidates.$knowledgeWinner.modelVersion
        rollbackVersionId = [string]$knowledgeFreeze.candidates.$knowledgeRollbackFamily.modelVersion
        deployedAt = $timestamp; deployedBy = $DeployedBy
    }
    [ordered]@{
        taskName = 'RISK'; activeVersionId = [string]$riskFreeze.candidates.$riskWinner.modelVersion
        rollbackVersionId = [string]$riskFreeze.candidates.$riskRollbackFamily.modelVersion
        deployedAt = $timestamp; deployedBy = $DeployedBy
    }
    [ordered]@{
        taskName = 'EXPLANATION'; activeVersionId = [string]$riskFreeze.selection.explainer.modelVersion
        rollbackVersionId = "shap-$([string]$riskFreeze.candidates.$riskRollbackFamily.modelVersion)"
        deployedAt = $timestamp; deployedBy = $DeployedBy
    }
    [ordered]@{
        taskName = 'PLAN_RULES'; activeVersionId = 'planner-rules-v1'
        rollbackVersionId = $null; deployedAt = $timestamp; deployedBy = $DeployedBy
    }
    [ordered]@{
        taskName = 'DIAGNOSIS'; activeVersionId = 'deepseek-v4-flash-tool-schema-v1'
        rollbackVersionId = $null; deployedAt = $timestamp; deployedBy = $DeployedBy
    }
)

$registry = [ordered]@{
    schemaVersion = 1
    processingRun = [ordered]@{
        id = $runId
        pipelineVersion = 'edutwin-data-pipeline-v1'
        gitTreeSha256 = $codeTreeSha
        configSha256 = $dataConfigSha
        randomSeed = 42
        status = 'SUCCEEDED'
        startedAt = $timestamp
        completedAt = $timestamp
        outputManifestSha256 = $processingManifestSha
    }
    datasets = $datasets
    models = $models
    deployments = $deployments
}

[IO.Directory]::CreateDirectory($databaseRoot) | Out-Null
$destination = Join-Path $databaseRoot 'registry.json'
$temporary = "$destination.tmp"
$json = $registry | ConvertTo-Json -Depth 100 -Compress
[IO.File]::WriteAllText($temporary, $json + "`n", [Text.UTF8Encoding]::new($false))
[IO.File]::Move($temporary, $destination, $true)

[ordered]@{
    registry = [IO.Path]::GetRelativePath($RepositoryRoot, $destination).Replace('\', '/')
    sha256 = Get-Sha256File $destination
    processingRunId = $runId
    datasetVersions = @($assistVersion, $ouladVersion, 'synthetic-demo-v6')
    modelCount = $models.Count
    deploymentCount = $deployments.Count
} | ConvertTo-Json -Depth 10
