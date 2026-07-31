Set-StrictMode -Version Latest

function Get-UtcTimestamp {
    return [DateTimeOffset]::UtcNow.ToString('o')
}

function Get-RepositoryRelativePath {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$RepositoryRoot
    )

    $resolved = [System.IO.Path]::GetFullPath($Path)
    $root = [System.IO.Path]::GetFullPath($RepositoryRoot)
    $relative = [System.IO.Path]::GetRelativePath($root, $resolved)
    if ($relative -eq '..' -or $relative.StartsWith("..$([System.IO.Path]::DirectorySeparatorChar)")) {
        throw "Evidence path is outside the repository: $resolved"
    }
    return $relative.Replace('\', '/')
}

function Get-FileReference {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$RepositoryRoot
    )

    $resolved = [System.IO.Path]::GetFullPath($Path)
    if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) {
        throw "Evidence file is missing: $resolved"
    }
    return [ordered]@{
        path = Get-RepositoryRelativePath -Path $resolved -RepositoryRoot $RepositoryRoot
        bytes = (Get-Item -LiteralPath $resolved).Length
        sha256 = (Get-FileHash -LiteralPath $resolved -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}

function Get-CodeTreeHash {
    param([Parameter(Mandatory)][string]$RepositoryRoot)

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = 'git'
    $startInfo.WorkingDirectory = [System.IO.Path]::GetFullPath($RepositoryRoot)
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.StandardOutputEncoding = [System.Text.Encoding]::UTF8
    foreach ($argument in @('ls-files', '-z', '--cached', '--others', '--exclude-standard')) {
        [void]$startInfo.ArgumentList.Add($argument)
    }
    $process = [System.Diagnostics.Process]::Start($startInfo)
    $raw = $process.StandardOutput.ReadToEnd()
    $errorOutput = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        throw "Git file enumeration failed: $errorOutput"
    }
    $paths = [string[]]$raw.Split(
        [char]0,
        [System.StringSplitOptions]::RemoveEmptyEntries)
    [Array]::Sort($paths, [System.StringComparer]::Ordinal)
    if ($paths.Count -eq 0) {
        throw 'The code tree contains no hashable files.'
    }
    $digest = [System.Security.Cryptography.IncrementalHash]::CreateHash(
        [System.Security.Cryptography.HashAlgorithmName]::SHA256
    )
    try {
        foreach ($relativePath in $paths) {
            $normalized = $relativePath.Replace('\', '/')
            $pathBytes = [System.Text.Encoding]::UTF8.GetBytes($normalized)
            $digest.AppendData([System.BitConverter]::GetBytes($pathBytes.Length))
            $digest.AppendData($pathBytes)
            $filePath = Join-Path $RepositoryRoot $relativePath
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

function New-AcceptanceManifest {
    param(
        [Parameter(Mandatory)][ValidateSet('Local', 'Server', 'All')][string]$Target,
        [Parameter(Mandatory)][string]$RunId,
        [Parameter(Mandatory)][string]$CodeTreeSha256,
        [Parameter(Mandatory)][string]$StartedAt,
        [Parameter(Mandatory)][string]$PythonRecordPath
    )

    $localRequested = $Target -in @('Local', 'All')
    $serverRequested = $Target -in @('Server', 'All')
    return [ordered]@{
        schemaVersion = 2
        kind = 'edutwin-delivery-acceptance'
        runId = $RunId
        target = $Target
        status = 'FAILED'
        startedAt = $StartedAt
        endedAt = $StartedAt
        codeTree = [ordered]@{
            algorithm = 'SHA-256'
            sha256 = $CodeTreeSha256
        }
        pythonEnvironment = [ordered]@{
            status = 'UNCONFIRMED'
            recordPath = $PythonRecordPath.Replace('\', '/')
            selectionKind = $null
            command = $null
            workingDirectory = $null
        }
        data = [ordered]@{
            sourceLock = $null
            processingManifest = $null
            reproducibilityGate = 'data.reproducibility'
            datasets = @()
        }
        models = [ordered]@{
            knowledgeFreeze = $null
            knowledgeTestMetrics = $null
            riskFreeze = $null
            riskTestMetrics = $null
            artifacts = @()
            versions = @()
            testEvaluations = @()
        }
        images = @()
        migrations = [ordered]@{
            local = @()
            server = @()
        }
        results = [ordered]@{
            local = [ordered]@{
                liveContracts = $null
                closedLoop = $null
                deepSeekToolCall = $null
                resilience = $null
                performance = $null
                restartRecovery = $null
                modelRollback = $null
                runtime = $null
            }
            server = [ordered]@{
                boundary = $null
                liveContracts = $null
                closedLoop = $null
                performance = $null
                restartRecovery = $null
                rollback = $null
                runtime = $null
            }
        }
        gates = @()
        requirements = @()
        local = [ordered]@{
            requested = $localRequested
            status = if ($localRequested) { 'FAILED' } else { 'NOT_REQUESTED' }
            gateIds = @()
            evidence = @()
        }
        server = [ordered]@{
            requested = $serverRequested
            status = if ($serverRequested) { 'FAILED' } else { 'NOT_REQUESTED' }
            gateIds = @()
            evidence = @()
        }
        openItems = @()
    }
}

function Get-ExpectedGateIds {
    param([Parameter(Mandatory)][ValidateSet('Local', 'Server', 'All')][string]$Target)

    $ids = @(
        'contract.schemas',
        'backend.integration',
        'python.ruff',
        'python.mypy',
        'python.pytest',
        'data.sources',
        'data.reproducibility',
        'models.knowledge',
        'models.risk',
        'demo.bootstrap',
        'demo.bootstrap-verify',
        'demo.registry',
        'frontend.install',
        'frontend.typecheck',
        'frontend.test',
        'frontend.build',
        'delivery.evidence'
    )
    if ($Target -in @('Local', 'All')) {
        $ids += @(
            'local.compose',
            'local.live-contracts',
            'local.closed-loop',
            'local.deepseek-tool-call',
            'local.resilience',
            'local.performance',
            'local.restart-recovery',
            'local.model-rollback',
            'local.runtime-evidence'
        )
    }
    if ($Target -in @('Server', 'All')) {
        $ids += @(
            'server.deploy',
            'server.boundary',
            'server.closed-loop',
            'server.live-contracts',
            'server.performance',
            'server.restart-recovery',
            'server.rollback',
            'server.runtime-evidence'
        )
    }
    if ($Target -eq 'All') {
        $ids += 'server.image-digest-parity'
    }
    return @($ids)
}

function Get-RequirementDefinitions {
    param([Parameter(Mandatory)][ValidateSet('Local', 'Server', 'All')][string]$Target)

    $runtimeScopes = @()
    if ($Target -in @('Local', 'All')) {
        $runtimeScopes += 'local.closed-loop'
    }
    if ($Target -in @('Server', 'All')) {
        $runtimeScopes += 'server.closed-loop'
    }
    $liveContracts = @()
    $performance = @()
    if ($Target -in @('Local', 'All')) {
        $liveContracts += 'local.live-contracts'
        $performance += 'local.performance'
    }
    if ($Target -in @('Server', 'All')) {
        $liveContracts += 'server.live-contracts'
        $performance += 'server.performance'
    }

    $definitions = @(
        @{ id = 'CONTRACT-001'; gates = @('contract.schemas') + $liveContracts },
        @{ id = 'CONTRACT-002'; gates = @('contract.schemas', 'backend.integration') },
        @{ id = 'AUTH-001'; gates = @('backend.integration') + $liveContracts },
        @{ id = 'AUTH-002'; gates = @('backend.integration') + $liveContracts },
        @{ id = 'AUTH-003'; gates = @('backend.integration') + $liveContracts },
        @{ id = 'AUTH-004'; gates = @('backend.integration') },
        @{ id = 'AUTH-005'; gates = @('backend.integration', 'frontend.test') + $liveContracts },
        @{ id = 'AUTH-006'; gates = @('backend.integration', 'frontend.test') + $liveContracts },
        @{ id = 'PROFILE-001'; gates = @('backend.integration', 'frontend.test') },
        @{ id = 'NOTIFY-001'; gates = @('backend.integration', 'frontend.test') },
        @{ id = 'LMS-001'; gates = @('backend.integration', 'frontend.test') + $liveContracts },
        @{ id = 'LMS-002'; gates = @('backend.integration', 'frontend.test') + $liveContracts },
        @{ id = 'RISK-001'; gates = @('backend.integration', 'frontend.test') + $liveContracts },
        @{ id = 'PLAN-001'; gates = @('backend.integration', 'frontend.test') + $runtimeScopes },
        @{ id = 'COUNSELOR-001'; gates = @('backend.integration', 'frontend.test') + $liveContracts },
        @{ id = 'ASSISTANT-001'; gates = @('backend.integration', 'frontend.test') },
        @{ id = 'ASSISTANT-002'; gates = @('backend.integration', 'frontend.test') + $liveContracts },
        @{ id = 'GOV-001'; gates = @('backend.integration', 'frontend.test') },
        @{ id = 'GOV-002'; gates = @('backend.integration', 'frontend.test') },
        @{ id = 'AUDIT-001'; gates = @('backend.integration', 'frontend.test') },
        @{ id = 'DEMO-001'; gates = @(
                'python.pytest', 'data.reproducibility', 'demo.bootstrap-verify', 'demo.registry'
            ) },
        @{ id = 'IDEMP-001'; gates = @('backend.integration') + $runtimeScopes },
        @{ id = 'IDEMP-002'; gates = @('backend.integration') + $runtimeScopes },
        @{ id = 'DATA-001'; gates = @('data.sources', 'data.reproducibility') },
        @{ id = 'DATA-002'; gates = @('python.pytest', 'data.reproducibility') },
        @{ id = 'DATA-003'; gates = @('python.pytest', 'data.reproducibility') },
        @{ id = 'DATA-004'; gates = @('data.reproducibility') },
        @{ id = 'DATA-005'; gates = @(
                'demo.bootstrap', 'demo.bootstrap-verify', 'demo.registry'
            ) },
        @{ id = 'MODEL-001'; gates = @('python.pytest', 'models.knowledge') },
        @{ id = 'MODEL-002'; gates = @('python.pytest', 'models.risk') },
        @{ id = 'MODEL-003'; gates = @('models.knowledge', 'models.risk', 'delivery.evidence') },
        @{ id = 'MODEL-004'; gates = @('python.pytest') + $runtimeScopes },
        @{ id = 'LOOP-001'; gates = $runtimeScopes },
        @{ id = 'LOOP-002'; gates = $runtimeScopes },
        @{ id = 'LOOP-003'; gates = $runtimeScopes },
        @{ id = 'LOOP-004'; gates = $runtimeScopes },
        @{ id = 'SSE-001'; gates = $runtimeScopes },
        @{ id = 'PERF-001'; gates = $performance },
        @{ id = 'PERF-002'; gates = $performance },
        @{ id = 'PERF-003'; gates = $performance },
        @{ id = 'PERF-004'; gates = $performance }
    )
    if ($Target -in @('Local', 'All')) {
        $definitions += @(
            @{ id = 'IMMUT-001'; gates = @('backend.integration', 'local.resilience') },
            @{ id = 'CACHE-001'; gates = @('local.resilience') },
            @{ id = 'REDIS-001'; gates = @('local.resilience') },
            @{ id = 'FALLBACK-001'; gates = @('local.resilience') },
            @{ id = 'FALLBACK-002'; gates = @('local.resilience') },
            @{ id = 'AI-001'; gates = @('local.deepseek-tool-call') },
            @{ id = 'OPS-001'; gates = @('local.compose', 'local.runtime-evidence') },
            @{ id = 'OPS-002'; gates = @('local.restart-recovery') },
            @{ id = 'OPS-003'; gates = @('local.model-rollback') }
        )
    }
    if ($Target -in @('Server', 'All')) {
        $definitions += @(
            @{ id = 'SERVER-001'; gates = @('server.deploy', 'server.runtime-evidence') },
            @{ id = 'SERVER-002'; gates = @('server.boundary', 'server.runtime-evidence') },
            @{ id = 'SERVER-003'; gates = @(
                    'server.closed-loop', 'server.live-contracts', 'server.performance',
                    'server.restart-recovery', 'server.rollback'
                ) }
        )
        if ($Target -eq 'All') {
            $definitions | Where-Object id -eq 'SERVER-001' | ForEach-Object {
                $_.gates += 'server.image-digest-parity'
            }
        }
    }
    return @($definitions)
}

function Complete-AcceptanceCoverage {
    param([Parameter(Mandatory)][System.Collections.IDictionary]$Manifest)

    $expectedGateIds = @(Get-ExpectedGateIds -Target $Manifest.target)
    foreach ($gateId in $expectedGateIds) {
        if (@($Manifest.gates | Where-Object id -eq $gateId).Count -ne 1) {
            Add-OpenItem `
                -Manifest $Manifest `
                -Id "missing-gate.$gateId" `
                -Scope Shared `
                -Message "Required gate was not recorded: $gateId" `
                -Evidence @('scripts/verify-delivery.ps1')
        }
    }

    $Manifest.requirements = @(
        foreach ($definition in Get-RequirementDefinitions -Target $Manifest.target) {
            $gateIds = @($definition.gates | Select-Object -Unique)
            $passed = $gateIds.Count -gt 0
            foreach ($gateId in $gateIds) {
                if ((Get-GateStatus -Manifest $Manifest -Id $gateId) -ne 'PASSED') {
                    $passed = $false
                }
            }
            [ordered]@{
                id = [string]$definition.id
                required = $true
                gateIds = $gateIds
                status = if ($passed) { 'PASSED' } else { 'FAILED' }
            }
        }
    )
}

function Add-OpenItem {
    param(
        [Parameter(Mandatory)][System.Collections.IDictionary]$Manifest,
        [Parameter(Mandatory)][string]$Id,
        [Parameter(Mandatory)][ValidateSet('Shared', 'Local', 'Server')][string]$Scope,
        [Parameter(Mandatory)][string]$Message,
        [Parameter(Mandatory)][string[]]$Evidence
    )

    if (@($Manifest.openItems | Where-Object { $_.id -eq $Id }).Count -gt 0) {
        return
    }
    $Manifest.openItems = @($Manifest.openItems) + [ordered]@{
        id = $Id
        scope = $Scope
        message = $Message
        evidence = @($Evidence)
    }
}

function Add-GateResult {
    param(
        [Parameter(Mandatory)][System.Collections.IDictionary]$Manifest,
        [Parameter(Mandatory)][System.Collections.IDictionary]$Gate
    )

    if (@($Manifest.gates | Where-Object { $_.id -eq $Gate.id }).Count -gt 0) {
        throw "Duplicate verification gate id: $($Gate.id)"
    }
    $Manifest.gates = @($Manifest.gates) + $Gate
    if ($Gate.scope -eq 'Local') {
        $Manifest.local.gateIds = @($Manifest.local.gateIds) + $Gate.id
        $Manifest.local.evidence = @($Manifest.local.evidence) + @($Gate.evidence)
    }
    elseif ($Gate.scope -eq 'Server') {
        $Manifest.server.gateIds = @($Manifest.server.gateIds) + $Gate.id
        $Manifest.server.evidence = @($Manifest.server.evidence) + @($Gate.evidence)
    }
    if ($Gate.status -eq 'FAILED') {
        Add-OpenItem `
            -Manifest $Manifest `
            -Id "gate.$($Gate.id)" `
            -Scope $Gate.scope `
            -Message $Gate.message `
            -Evidence $Gate.evidence
    }
}

function New-GateRecord {
    param(
        [Parameter(Mandatory)][string]$Id,
        [Parameter(Mandatory)][string]$Category,
        [Parameter(Mandatory)][ValidateSet('Shared', 'Local', 'Server')][string]$Scope,
        [Parameter(Mandatory)][ValidateSet('PASSED', 'FAILED')][string]$Status,
        [Parameter(Mandatory)][string]$StartedAt,
        [Parameter(Mandatory)][string]$EndedAt,
        [Parameter(Mandatory)][long]$DurationMs,
        [Parameter(Mandatory)][int]$ExitCode,
        [Parameter(Mandatory)][string]$Command,
        [Parameter(Mandatory)][string[]]$Evidence,
        [Parameter(Mandatory)][AllowEmptyString()][string]$Message
    )

    return [ordered]@{
        id = $Id
        category = $Category
        scope = $Scope
        required = $true
        status = $Status
        startedAt = $StartedAt
        endedAt = $EndedAt
        durationMs = $DurationMs
        exitCode = $ExitCode
        command = $Command
        evidence = @($Evidence)
        message = $Message
    }
}

function Get-GateLogPath {
    param(
        [Parameter(Mandatory)][string]$EvidenceDirectory,
        [Parameter(Mandatory)][string]$Id
    )

    New-Item -Path $EvidenceDirectory -ItemType Directory -Force | Out-Null
    return Join-Path $EvidenceDirectory "$Id.log"
}

function Invoke-FailedGate {
    param(
        [Parameter(Mandatory)][System.Collections.IDictionary]$Manifest,
        [Parameter(Mandatory)][string]$RepositoryRoot,
        [Parameter(Mandatory)][string]$EvidenceDirectory,
        [Parameter(Mandatory)][string]$Id,
        [Parameter(Mandatory)][string]$Category,
        [Parameter(Mandatory)][ValidateSet('Shared', 'Local', 'Server')][string]$Scope,
        [Parameter(Mandatory)][string]$Command,
        [Parameter(Mandatory)][string]$Message,
        [int]$ExitCode = 2
    )

    $started = [DateTimeOffset]::UtcNow
    $logPath = Get-GateLogPath -EvidenceDirectory $EvidenceDirectory -Id $Id
    [System.IO.File]::WriteAllText(
        $logPath,
        "$Message`n",
        [System.Text.UTF8Encoding]::new($false)
    )
    $ended = [DateTimeOffset]::UtcNow
    $relativeLog = Get-RepositoryRelativePath -Path $logPath -RepositoryRoot $RepositoryRoot
    $gate = New-GateRecord `
        -Id $Id `
        -Category $Category `
        -Scope $Scope `
        -Status 'FAILED' `
        -StartedAt $started.ToString('o') `
        -EndedAt $ended.ToString('o') `
        -DurationMs ([long][Math]::Max(0, ($ended - $started).TotalMilliseconds)) `
        -ExitCode $ExitCode `
        -Command $Command `
        -Evidence @($relativeLog) `
        -Message $Message
    Add-GateResult -Manifest $Manifest -Gate $gate
    return $gate
}

function Format-CommandLine {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [string[]]$Arguments = @()
    )

    $parts = @($FilePath)
    foreach ($argument in $Arguments) {
        if ($argument -match '[\s"]') {
            $parts += '"' + $argument.Replace('"', '\"') + '"'
        }
        else {
            $parts += $argument
        }
    }
    return [string]::Join(' ', $parts)
}

function Invoke-ExternalGate {
    param(
        [Parameter(Mandatory)][System.Collections.IDictionary]$Manifest,
        [Parameter(Mandatory)][string]$RepositoryRoot,
        [Parameter(Mandatory)][string]$EvidenceDirectory,
        [Parameter(Mandatory)][string]$Id,
        [Parameter(Mandatory)][string]$Category,
        [Parameter(Mandatory)][ValidateSet('Shared', 'Local', 'Server')][string]$Scope,
        [Parameter(Mandatory)][string]$FilePath,
        [string[]]$Arguments = @(),
        [string]$WorkingDirectory = $RepositoryRoot,
        [hashtable]$Environment = @{}
    )

    $display = Format-CommandLine -FilePath $FilePath -Arguments $Arguments
    $isPath = [System.IO.Path]::IsPathRooted($FilePath) -or
        $FilePath.Contains([System.IO.Path]::DirectorySeparatorChar) -or
        $FilePath.Contains([System.IO.Path]::AltDirectorySeparatorChar)
    if (($isPath -and -not (Test-Path -LiteralPath $FilePath -PathType Leaf)) -or
        (-not $isPath -and $null -eq (Get-Command $FilePath -ErrorAction SilentlyContinue))) {
        return Invoke-FailedGate `
            -Manifest $Manifest `
            -RepositoryRoot $RepositoryRoot `
            -EvidenceDirectory $EvidenceDirectory `
            -Id $Id `
            -Category $Category `
            -Scope $Scope `
            -Command $display `
            -Message "Required command is missing: $FilePath" `
            -ExitCode 127
    }
    if (-not (Test-Path -LiteralPath $WorkingDirectory -PathType Container)) {
        return Invoke-FailedGate `
            -Manifest $Manifest `
            -RepositoryRoot $RepositoryRoot `
            -EvidenceDirectory $EvidenceDirectory `
            -Id $Id `
            -Category $Category `
            -Scope $Scope `
            -Command $display `
            -Message "Required working directory is missing: $WorkingDirectory" `
            -ExitCode 127
    }

    $started = [DateTimeOffset]::UtcNow
    $logPath = Get-GateLogPath -EvidenceDirectory $EvidenceDirectory -Id $Id
    [System.IO.File]::WriteAllText(
        $logPath,
        "command=$display`nworkingDirectory=$WorkingDirectory`n",
        [System.Text.UTF8Encoding]::new($false)
    )
    $previousEnvironment = @{}
    foreach ($name in $Environment.Keys) {
        $previousEnvironment[$name] = [System.Environment]::GetEnvironmentVariable($name, 'Process')
        [System.Environment]::SetEnvironmentVariable($name, [string]$Environment[$name], 'Process')
    }
    $status = 'FAILED'
    $exitCode = 1
    $message = ''
    Push-Location $WorkingDirectory
    try {
        $PSNativeCommandUseErrorActionPreference = $false
        & $FilePath @Arguments *>> $logPath
        $exitCode = if ($null -eq $LASTEXITCODE) { 0 } else { [int]$LASTEXITCODE }
        if ($exitCode -ne 0) {
            $message = "Gate command exited with code $exitCode."
        }
        else {
            $status = 'PASSED'
        }
    }
    catch {
        $message = $_.Exception.Message
        $_ | Out-String | Add-Content -LiteralPath $logPath -Encoding utf8
    }
    finally {
        Pop-Location
        foreach ($name in $Environment.Keys) {
            [System.Environment]::SetEnvironmentVariable(
                $name,
                $previousEnvironment[$name],
                'Process'
            )
        }
    }
    $ended = [DateTimeOffset]::UtcNow
    $relativeLog = Get-RepositoryRelativePath -Path $logPath -RepositoryRoot $RepositoryRoot
    $gate = New-GateRecord `
        -Id $Id `
        -Category $Category `
        -Scope $Scope `
        -Status $status `
        -StartedAt $started.ToString('o') `
        -EndedAt $ended.ToString('o') `
        -DurationMs ([long][Math]::Max(0, ($ended - $started).TotalMilliseconds)) `
        -ExitCode $exitCode `
        -Command $display `
        -Evidence @($relativeLog) `
        -Message $message
    Add-GateResult -Manifest $Manifest -Gate $gate
    return $gate
}

function Invoke-CheckGate {
    param(
        [Parameter(Mandatory)][System.Collections.IDictionary]$Manifest,
        [Parameter(Mandatory)][string]$RepositoryRoot,
        [Parameter(Mandatory)][string]$EvidenceDirectory,
        [Parameter(Mandatory)][string]$Id,
        [Parameter(Mandatory)][string]$Category,
        [Parameter(Mandatory)][ValidateSet('Shared', 'Local', 'Server')][string]$Scope,
        [Parameter(Mandatory)][string]$Command,
        [Parameter(Mandatory)][scriptblock]$Action
    )

    $started = [DateTimeOffset]::UtcNow
    $logPath = Get-GateLogPath -EvidenceDirectory $EvidenceDirectory -Id $Id
    [System.IO.File]::WriteAllText(
        $logPath,
        "check=$Command`n",
        [System.Text.UTF8Encoding]::new($false)
    )
    $status = 'FAILED'
    $exitCode = 1
    $message = ''
    try {
        & $Action *>> $logPath
        $status = 'PASSED'
        $exitCode = 0
    }
    catch {
        $message = $_.Exception.Message
        $_ | Out-String | Add-Content -LiteralPath $logPath -Encoding utf8
    }
    $ended = [DateTimeOffset]::UtcNow
    $relativeLog = Get-RepositoryRelativePath -Path $logPath -RepositoryRoot $RepositoryRoot
    $gate = New-GateRecord `
        -Id $Id `
        -Category $Category `
        -Scope $Scope `
        -Status $status `
        -StartedAt $started.ToString('o') `
        -EndedAt $ended.ToString('o') `
        -DurationMs ([long][Math]::Max(0, ($ended - $started).TotalMilliseconds)) `
        -ExitCode $exitCode `
        -Command $Command `
        -Evidence @($relativeLog) `
        -Message $message
    Add-GateResult -Manifest $Manifest -Gate $gate
    return $gate
}

function Invoke-ScriptGate {
    param(
        [Parameter(Mandatory)][System.Collections.IDictionary]$Manifest,
        [Parameter(Mandatory)][string]$RepositoryRoot,
        [Parameter(Mandatory)][string]$EvidenceDirectory,
        [Parameter(Mandatory)][string]$Id,
        [Parameter(Mandatory)][string]$Category,
        [Parameter(Mandatory)][ValidateSet('Shared', 'Local', 'Server')][string]$Scope,
        [Parameter(Mandatory)][string]$ScriptPath,
        [string[]]$Arguments = @(),
        [string]$WorkingDirectory = $RepositoryRoot,
        [hashtable]$Environment = @{}
    )

    if (-not (Test-Path -LiteralPath $ScriptPath -PathType Leaf)) {
        return Invoke-FailedGate `
            -Manifest $Manifest `
            -RepositoryRoot $RepositoryRoot `
            -EvidenceDirectory $EvidenceDirectory `
            -Id $Id `
            -Category $Category `
            -Scope $Scope `
            -Command "pwsh -NoProfile -File $ScriptPath" `
            -Message "Required verification script is missing: $ScriptPath" `
            -ExitCode 127
    }
    return Invoke-ExternalGate `
        -Manifest $Manifest `
        -RepositoryRoot $RepositoryRoot `
        -EvidenceDirectory $EvidenceDirectory `
        -Id $Id `
        -Category $Category `
        -Scope $Scope `
        -FilePath 'pwsh' `
        -Arguments (@('-NoProfile', '-File', $ScriptPath) + $Arguments) `
        -WorkingDirectory $WorkingDirectory `
        -Environment $Environment
}

function Get-GateStatus {
    param(
        [Parameter(Mandatory)][System.Collections.IDictionary]$Manifest,
        [Parameter(Mandatory)][string]$Id
    )

    $matches = @($Manifest.gates | Where-Object { $_.id -eq $Id })
    if ($matches.Count -ne 1) {
        return $null
    }
    return [string]$matches[0].status
}

function Resolve-ConfirmedPythonEnvironment {
    param(
        [Parameter(Mandatory)][string]$RepositoryRoot,
        [Parameter(Mandatory)][System.Collections.IDictionary]$Manifest
    )

    $recordPath = Join-Path $RepositoryRoot '.codex-python-env.local.json'
    if (-not (Test-Path -LiteralPath $recordPath -PathType Leaf)) {
        return [pscustomobject]@{
            confirmed = $false
            message = "Python environment confirmation record is missing: $recordPath"
            executable = $null
            prefixArguments = @()
            workingDirectory = $null
        }
    }
    try {
        $record = Get-Content -LiteralPath $recordPath -Raw | ConvertFrom-Json
    }
    catch {
        return [pscustomobject]@{
            confirmed = $false
            message = "Python environment confirmation record is invalid JSON: $recordPath"
            executable = $null
            prefixArguments = @()
            workingDirectory = $null
        }
    }
    $recordRoot = [System.IO.Path]::GetFullPath([string]$record.project_root)
    if (-not $record.project_root -or
        -not $recordRoot.Equals(
            [System.IO.Path]::GetFullPath($RepositoryRoot),
            [System.StringComparison]::OrdinalIgnoreCase
        )) {
        return [pscustomobject]@{
            confirmed = $false
            message = 'Python environment record belongs to a different project root.'
            executable = $null
            prefixArguments = @()
            workingDirectory = $null
        }
    }
    $workingDirectory = if ($record.working_directory) {
        [System.IO.Path]::GetFullPath([string]$record.working_directory)
    }
    else {
        [System.IO.Path]::GetFullPath($RepositoryRoot)
    }
    if (-not (Test-Path -LiteralPath $workingDirectory -PathType Container)) {
        return [pscustomobject]@{
            confirmed = $false
            message = "Python environment working directory is missing: $workingDirectory"
            executable = $null
            prefixArguments = @()
            workingDirectory = $workingDirectory
        }
    }

    if ($record.selection_kind -eq 'python_path') {
        $pythonPath = [System.IO.Path]::GetFullPath([string]$record.python_path)
        if (-not $record.python_path -or
            -not (Test-Path -LiteralPath $pythonPath -PathType Leaf)) {
            return [pscustomobject]@{
                confirmed = $false
                message = "Confirmed Python interpreter is missing: $pythonPath"
                executable = $null
                prefixArguments = @()
                workingDirectory = $workingDirectory
            }
        }
        $Manifest.pythonEnvironment.status = 'CONFIRMED'
        $Manifest.pythonEnvironment.selectionKind = 'python_path'
        $Manifest.pythonEnvironment.command = $pythonPath
        $Manifest.pythonEnvironment.workingDirectory = $workingDirectory
        return [pscustomobject]@{
            confirmed = $true
            message = ''
            executable = $pythonPath
            prefixArguments = @()
            workingDirectory = $workingDirectory
        }
    }

    if ($record.selection_kind -eq 'command_prefix') {
        $prefix = @($record.command_prefix | ForEach-Object { [string]$_ })
        if ($prefix.Count -eq 0 -or
            $null -eq (Get-Command $prefix[0] -ErrorAction SilentlyContinue)) {
            return [pscustomobject]@{
                confirmed = $false
                message = 'Confirmed Python command prefix is missing or unavailable.'
                executable = $null
                prefixArguments = @()
                workingDirectory = $workingDirectory
            }
        }
        $Manifest.pythonEnvironment.status = 'CONFIRMED'
        $Manifest.pythonEnvironment.selectionKind = 'command_prefix'
        $Manifest.pythonEnvironment.command = [string]::Join(' ', $prefix)
        $Manifest.pythonEnvironment.workingDirectory = $workingDirectory
        return [pscustomobject]@{
            confirmed = $true
            message = ''
            executable = $prefix[0]
            prefixArguments = @($prefix | Select-Object -Skip 1)
            workingDirectory = $workingDirectory
        }
    }

    return [pscustomobject]@{
        confirmed = $false
        message = "Unsupported Python environment selection: $($record.selection_kind)"
        executable = $null
        prefixArguments = @()
        workingDirectory = $workingDirectory
    }
}

function Invoke-PythonGate {
    param(
        [Parameter(Mandatory)][System.Collections.IDictionary]$Manifest,
        [Parameter(Mandatory)][psobject]$PythonEnvironment,
        [Parameter(Mandatory)][string]$RepositoryRoot,
        [Parameter(Mandatory)][string]$EvidenceDirectory,
        [Parameter(Mandatory)][string]$Id,
        [Parameter(Mandatory)][string]$Category,
        [Parameter(Mandatory)][string[]]$Arguments,
        [Parameter(Mandatory)][string]$WorkingDirectory
    )

    if (-not $PythonEnvironment.confirmed) {
        return Invoke-FailedGate `
            -Manifest $Manifest `
            -RepositoryRoot $RepositoryRoot `
            -EvidenceDirectory $EvidenceDirectory `
            -Id $Id `
            -Category $Category `
            -Scope 'Shared' `
            -Command "confirmed-python $([string]::Join(' ', $Arguments))" `
            -Message $PythonEnvironment.message `
            -ExitCode 126
    }
    return Invoke-ExternalGate `
        -Manifest $Manifest `
        -RepositoryRoot $RepositoryRoot `
        -EvidenceDirectory $EvidenceDirectory `
        -Id $Id `
        -Category $Category `
        -Scope 'Shared' `
        -FilePath $PythonEnvironment.executable `
        -Arguments (@($PythonEnvironment.prefixArguments) + $Arguments) `
        -WorkingDirectory $WorkingDirectory
}

function Update-AcceptanceStatus {
    param([Parameter(Mandatory)][System.Collections.IDictionary]$Manifest)

    Complete-AcceptanceCoverage -Manifest $Manifest
    $sharedFailed = @($Manifest.gates | Where-Object {
            $_.scope -eq 'Shared' -and $_.status -ne 'PASSED'
        }).Count -gt 0
    if ($Manifest.local.requested) {
        $localFailed = @($Manifest.gates | Where-Object {
                $_.scope -eq 'Local' -and $_.status -ne 'PASSED'
            }).Count -gt 0
        $Manifest.local.status = if ($sharedFailed -or $localFailed) { 'FAILED' } else { 'PASSED' }
    }
    if ($Manifest.server.requested) {
        $serverFailed = @($Manifest.gates | Where-Object {
                $_.scope -eq 'Server' -and $_.status -ne 'PASSED'
            }).Count -gt 0
        $Manifest.server.status = if ($sharedFailed -or $serverFailed) { 'FAILED' } else { 'PASSED' }
    }
    $requestedScopesPassed =
        (-not $Manifest.local.requested -or $Manifest.local.status -eq 'PASSED') -and
        (-not $Manifest.server.requested -or $Manifest.server.status -eq 'PASSED')
    $allGatesPassed = @($Manifest.gates | Where-Object { $_.status -ne 'PASSED' }).Count -eq 0
    $allRequirementsPassed = @(
        $Manifest.requirements | Where-Object { $_.status -ne 'PASSED' }
    ).Count -eq 0
    $Manifest.status = if (
        $requestedScopesPassed -and
        $allGatesPassed -and
        $allRequirementsPassed -and
        @($Manifest.openItems).Count -eq 0
    ) { 'PASSED' } else { 'FAILED' }
    $Manifest.endedAt = Get-UtcTimestamp
}

function Assert-AcceptanceSemantics {
    param([Parameter(Mandatory)][System.Collections.IDictionary]$Manifest)

    if (@($Manifest.gates).Count -eq 0) {
        throw 'Acceptance manifest contains no gates.'
    }
    if (@($Manifest.gates | Group-Object { $_.id } | Where-Object Count -ne 1).Count -gt 0) {
        throw 'Acceptance manifest contains duplicate gate identifiers.'
    }
    if (@($Manifest.gates | Where-Object { -not $_.required }).Count -gt 0) {
        throw 'Acceptance manifest contains a non-required gate.'
    }
    if (@($Manifest.requirements).Count -eq 0 -or
        @($Manifest.requirements | Group-Object { $_.id } | Where-Object Count -ne 1).Count -gt 0) {
        throw 'Acceptance manifest requirement coverage is empty or contains duplicate IDs.'
    }
    if ($Manifest.local.requested -and $Manifest.local.status -eq 'NOT_REQUESTED') {
        throw 'Requested local acceptance is marked not requested.'
    }
    if ($Manifest.server.requested -and $Manifest.server.status -eq 'NOT_REQUESTED') {
        throw 'Requested server acceptance is marked not requested.'
    }
    if ($Manifest.status -eq 'PASSED') {
        if (@($Manifest.openItems).Count -ne 0) {
            throw 'A passed acceptance manifest contains open items.'
        }
        if (@($Manifest.gates | Where-Object { $_.status -ne 'PASSED' }).Count -ne 0) {
            throw 'A passed acceptance manifest contains failed gates.'
        }
        if (@($Manifest.requirements | Where-Object { $_.status -ne 'PASSED' }).Count -ne 0) {
            throw 'A passed acceptance manifest contains failed requirement coverage.'
        }
        $expectedGateIds = @(Get-ExpectedGateIds -Target $Manifest.target)
        $recordedGateIds = @($Manifest.gates | ForEach-Object id)
        if (@($expectedGateIds | Where-Object { $_ -notin $recordedGateIds }).Count -ne 0) {
            throw 'A passed acceptance manifest omits one or more required gates.'
        }
        foreach ($reference in @(
                $Manifest.data.sourceLock,
                $Manifest.data.processingManifest,
                $Manifest.models.knowledgeFreeze,
                $Manifest.models.knowledgeTestMetrics,
                $Manifest.models.riskFreeze,
                $Manifest.models.riskTestMetrics
            )) {
            if ($null -eq $reference) {
                throw 'A passed acceptance manifest is missing required data or model evidence.'
            }
        }
        if (@($Manifest.models.artifacts).Count -eq 0) {
            throw 'A passed acceptance manifest contains no model artifact hashes.'
        }
        if (@($Manifest.data.datasets).Count -ne 3) {
            throw 'A passed acceptance manifest must bind all three dataset versions.'
        }
        if (@($Manifest.models.versions | Where-Object task -eq 'KNOWLEDGE').Count -ne 4 -or
            @($Manifest.models.versions | Where-Object task -eq 'RISK').Count -ne 3 -or
            @($Manifest.models.testEvaluations).Count -ne 2) {
            throw 'A passed acceptance manifest lacks seven model versions or two test evaluations.'
        }
        if ($Manifest.local.requested) {
            if (@($Manifest.images | Where-Object scope -eq 'Local').Count -lt 3 -or
                @($Manifest.migrations.local).Count -eq 0) {
                throw 'Passed local acceptance lacks image or migration evidence.'
            }
            foreach ($reference in $Manifest.results.local.Values) {
                if ($null -eq $reference) {
                    throw 'Passed local acceptance lacks a required structured result report.'
                }
            }
        }
        if ($Manifest.server.requested) {
            if (@($Manifest.images | Where-Object scope -eq 'Server').Count -lt 3 -or
                @($Manifest.migrations.server).Count -eq 0) {
                throw 'Passed server acceptance lacks image or migration evidence.'
            }
            foreach ($reference in $Manifest.results.server.Values) {
                if ($null -eq $reference) {
                    throw 'Passed server acceptance lacks a required structured result report.'
                }
            }
        }
    }
}

function Write-AcceptanceManifest {
    param(
        [Parameter(Mandatory)][System.Collections.IDictionary]$Manifest,
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$SchemaPath
    )

    Assert-AcceptanceSemantics -Manifest $Manifest
    if (-not (Test-Path -LiteralPath $SchemaPath -PathType Leaf)) {
        throw "Acceptance JSON schema is missing: $SchemaPath"
    }
    $resolved = [System.IO.Path]::GetFullPath($Path)
    New-Item -Path (Split-Path -Parent $resolved) -ItemType Directory -Force | Out-Null
    $json = $Manifest | ConvertTo-Json -Depth 40
    $testJson = Get-Command Test-Json -ErrorAction SilentlyContinue
    if ($null -eq $testJson) {
        throw 'PowerShell Test-Json is required for strict manifest validation.'
    }
    if (-not ($json | Test-Json -SchemaFile $SchemaPath -ErrorAction Stop)) {
        throw 'Acceptance manifest does not satisfy the strict JSON schema.'
    }
    $temporary = "$resolved.tmp"
    try {
        [System.IO.File]::WriteAllText(
            $temporary,
            ($json + "`n"),
            [System.Text.UTF8Encoding]::new($false)
        )
        Move-Item -LiteralPath $temporary -Destination $resolved -Force
    }
    finally {
        if (Test-Path -LiteralPath $temporary) {
            Remove-Item -LiteralPath $temporary -Force
        }
    }
    return $resolved
}
