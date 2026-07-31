[CmdletBinding()]
param(
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$projectRoot = [System.IO.Path]::GetFullPath(
    (Join-Path (Split-Path -Parent $PSScriptRoot) '..')
)
$scriptPath = $PSCommandPath
$canonicalizerPath = Join-Path $PSScriptRoot 'AssistmentsCanonicalizer.java'
$rawRoot = Join-Path $projectRoot 'data/raw'
$manifestPath = Join-Path $projectRoot 'data/manifests/sources.yaml'
$lockPath = Join-Path $projectRoot 'data/manifests/source-lock.json'

$latin1 = [System.Text.Encoding]::GetEncoding(28591)
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
$csvSpecialCharacters = [char[]]@(',', '"', "`r", "`n")
$assistmentsDownloadUrlVariable = 'EDUTWIN_ASSISTMENTS_DOWNLOAD_URL'
$assistmentsDownloadUrl = [Environment]::GetEnvironmentVariable(
    $assistmentsDownloadUrlVariable
)
$assistmentsOfficialPath = Join-Path $rawRoot 'assistments/official/skill_builder_data.csv'
$assistmentsDownloadRequired = $Force -or -not (
    Test-Path -LiteralPath $assistmentsOfficialPath -PathType Leaf
)
if ($assistmentsDownloadRequired -and [string]::IsNullOrWhiteSpace($assistmentsDownloadUrl)) {
    throw "$assistmentsDownloadUrlVariable is required to download ASSISTments."
}
$assistmentsDownloadUri = $null
if (-not [string]::IsNullOrWhiteSpace($assistmentsDownloadUrl) -and (
        -not [Uri]::TryCreate(
            $assistmentsDownloadUrl,
            [UriKind]::Absolute,
            [ref]$assistmentsDownloadUri
        ) -or $assistmentsDownloadUri.Scheme -ne 'https'
    )) {
    throw "$assistmentsDownloadUrlVariable must contain an absolute HTTPS URL."
}

$expectedAssistmentsHeader = [string[]]@(
    'order_id',
    'assignment_id',
    'user_id',
    'assistment_id',
    'problem_id',
    'original',
    'correct',
    'attempt_count',
    'ms_first_response',
    'tutor_mode',
    'answer_type',
    'sequence_id',
    'student_class_id',
    'position',
    'type',
    'base_sequence_id',
    'skill_id',
    'skill_name',
    'teacher_id',
    'school_id',
    'hint_count',
    'hint_total',
    'overlap_time',
    'template_id',
    'answer_id',
    'answer_text',
    'first_action',
    'bottom_hint',
    'opportunity',
    'opportunity_original'
)

$expectedOuladEntries = [string[]]@(
    'assessments.csv',
    'courses.csv',
    'studentAssessment.csv',
    'studentInfo.csv',
    'studentRegistration.csv',
    'studentVle.csv',
    'vle.csv'
)

$sources = [ordered]@{
    AssistmentsOfficial = [ordered]@{
        Url = $assistmentsDownloadUrl
        ExpectedBytes = [int64]83201940
        ExpectedRows = 525534
        Destination = $assistmentsOfficialPath
    }
    AssistmentsMirror = [ordered]@{
        Url = 'https://base.ustc.edu.cn/data/ASSISTment/2009_skill_builder_data_corrected.zip'
        ExpectedBytes = [int64]9084422
        ExpectedEntryBytes = [int64]63455745
        ExpectedEntryCompressedBytes = [int64]9084224
        ExpectedEntryCrc32 = 'E0A1CC26'
        ExpectedRows = 401756
        ExpectedUniqueOrderIds = 346860
        Archive = Join-Path $rawRoot 'assistments/mirror/2009_skill_builder_data_corrected.zip'
        Extracted = Join-Path $rawRoot 'assistments/mirror/skill_builder_data_corrected.csv'
    }
    AssistmentsCanonical = [ordered]@{
        ExpectedRows = 401756
        ExpectedUniqueOrderIds = 346860
        ExpectedRemovedRows = 123778
        Destination = Join-Path $rawRoot 'assistments/canonical/skill_builder_data_corrected.csv'
    }
    Oulad = [ordered]@{
        Url = 'https://ndownloader.figshare.com/files/8606371'
        ExpectedBytes = [int64]46750706
        ExpectedMd5 = '7412686fd77cf0e0ee1e8c3e9b354308'
        Archive = Join-Path $rawRoot 'oulad/anonymisedData.zip'
        ExtractedDirectory = Join-Path $rawRoot 'oulad/extracted'
    }
}

function Write-Stage {
    param([Parameter(Mandatory)][string]$Message)

    Write-Host ("[data] {0}" -f $Message)
}

function Get-RelativeProjectPath {
    param([Parameter(Mandatory)][string]$Path)

    return [System.IO.Path]::GetRelativePath($projectRoot, $Path).Replace('\', '/')
}

function Get-LowerFileHash {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][ValidateSet('MD5', 'SHA256')][string]$Algorithm
    )

    return (Get-FileHash -LiteralPath $Path -Algorithm $Algorithm).Hash.ToLowerInvariant()
}

function Assert-FileLength {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][int64]$ExpectedBytes
    )

    $actualBytes = (Get-Item -LiteralPath $Path).Length
    if ($actualBytes -ne $ExpectedBytes) {
        throw "File length mismatch for '$Path': expected $ExpectedBytes, got $actualBytes."
    }
}

function Assert-ZipMagic {
    param([Parameter(Mandatory)][string]$Path)

    $stream = [System.IO.File]::OpenRead($Path)
    try {
        $magic = [byte[]]::new(4)
        if ($stream.Read($magic, 0, $magic.Length) -ne $magic.Length) {
            throw "ZIP file is shorter than four bytes: '$Path'."
        }

        if (
            $magic[0] -ne 0x50 -or
            $magic[1] -ne 0x4B -or
            $magic[2] -ne 0x03 -or
            $magic[3] -ne 0x04
        ) {
            throw "ZIP magic mismatch for '$Path'."
        }
    }
    finally {
        $stream.Dispose()
    }
}

function Assert-AssistmentsHeaderPrefix {
    param([Parameter(Mandatory)][string]$Path)

    $reader = [System.IO.StreamReader]::new($Path, $latin1, $true)
    try {
        $actualHeader = $reader.ReadLine()
        $expectedHeader = [string]::Join(',', $expectedAssistmentsHeader)
        if ($actualHeader -ne $expectedHeader) {
            throw "ASSISTments header mismatch for '$Path'."
        }
    }
    finally {
        $reader.Dispose()
    }
}

function Invoke-CheckedDownload {
    param(
        [Parameter(Mandatory)][AllowEmptyString()][string]$Url,
        [Parameter(Mandatory)][string]$Destination,
        [Parameter(Mandatory)][int64]$ExpectedBytes,
        [Parameter(Mandatory)][ValidateSet('csv', 'zip')][string]$Kind,
        [Parameter(Mandatory)][string[]]$AcceptedContentTypes,
        [string]$ExpectedMd5
    )

    $destinationDirectory = Split-Path -Parent $Destination
    New-Item -Path $destinationDirectory -ItemType Directory -Force | Out-Null

    $downloaded = $false
    $finalUrl = $Url
    $contentType = $null

    if ($Force -or -not (Test-Path -LiteralPath $Destination -PathType Leaf)) {
        $partialPath = "$Destination.partial"
        if (Test-Path -LiteralPath $partialPath) {
            Remove-Item -LiteralPath $partialPath -Force
        }

        $handler = [System.Net.Http.HttpClientHandler]::new()
        $handler.AllowAutoRedirect = $true
        $handler.MaxAutomaticRedirections = 10
        $client = [System.Net.Http.HttpClient]::new($handler)
        $client.Timeout = [System.TimeSpan]::FromMinutes(30)
        $client.DefaultRequestHeaders.UserAgent.ParseAdd('EduTwin-source-lock/1.0')
        $response = $null
        $completed = $false

        try {
            $response = $client.GetAsync(
                [System.Uri]$Url,
                [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead
            ).GetAwaiter().GetResult()

            if ($response.StatusCode -ne [System.Net.HttpStatusCode]::OK) {
                throw "Download failed for '$Url' with HTTP $([int]$response.StatusCode)."
            }

            $finalUrl = $response.RequestMessage.RequestUri.GetLeftPart(
                [System.UriPartial]::Path
            )
            if ($response.RequestMessage.RequestUri.Scheme -ne 'https') {
                throw "Download redirected to a non-HTTPS endpoint: '$finalUrl'."
            }

            $contentTypeHeader = $response.Content.Headers.ContentType
            $contentType = if ($null -eq $contentTypeHeader) {
                $null
            }
            else {
                $contentTypeHeader.MediaType
            }
            if (
                [string]::IsNullOrWhiteSpace($contentType) -or
                $AcceptedContentTypes -notcontains $contentType
            ) {
                throw "Unexpected Content-Type '$contentType' for '$Url'."
            }

            $contentLength = $response.Content.Headers.ContentLength
            if ($null -eq $contentLength -or [int64]$contentLength -ne $ExpectedBytes) {
                throw "Unexpected Content-Length for '$Url': expected $ExpectedBytes, got $contentLength."
            }

            $inputStream = $response.Content.ReadAsStream()
            $outputStream = [System.IO.FileStream]::new(
                $partialPath,
                [System.IO.FileMode]::CreateNew,
                [System.IO.FileAccess]::Write,
                [System.IO.FileShare]::None
            )
            try {
                $inputStream.CopyTo($outputStream)
                $outputStream.Flush($true)
            }
            finally {
                $outputStream.Dispose()
                $inputStream.Dispose()
            }

            Assert-FileLength -Path $partialPath -ExpectedBytes $ExpectedBytes
            if ($Kind -eq 'zip') {
                Assert-ZipMagic -Path $partialPath
            }
            else {
                Assert-AssistmentsHeaderPrefix -Path $partialPath
            }
            if (-not [string]::IsNullOrWhiteSpace($ExpectedMd5)) {
                $partialMd5 = Get-LowerFileHash -Path $partialPath -Algorithm MD5
                if ($partialMd5 -ne $ExpectedMd5.ToLowerInvariant()) {
                    throw "MD5 mismatch for '$Url': expected $ExpectedMd5, got $partialMd5."
                }
            }

            Move-Item -LiteralPath $partialPath -Destination $Destination -Force
            $downloaded = $true
            $completed = $true
        }
        finally {
            if ($null -ne $response) {
                $response.Dispose()
            }
            $client.Dispose()
            $handler.Dispose()

            if (-not $completed -and (Test-Path -LiteralPath $partialPath)) {
                Remove-Item -LiteralPath $partialPath -Force
            }
        }
    }

    Assert-FileLength -Path $Destination -ExpectedBytes $ExpectedBytes
    if ($Kind -eq 'zip') {
        Assert-ZipMagic -Path $Destination
    }
    else {
        Assert-AssistmentsHeaderPrefix -Path $Destination
    }
    if (-not [string]::IsNullOrWhiteSpace($ExpectedMd5)) {
        $actualMd5 = Get-LowerFileHash -Path $Destination -Algorithm MD5
        if ($actualMd5 -ne $ExpectedMd5.ToLowerInvariant()) {
            throw "MD5 mismatch for '$Destination': expected $ExpectedMd5, got $actualMd5."
        }
    }

    return [pscustomobject][ordered]@{
        requested_url = $Url
        final_url = $finalUrl
        content_type = $contentType
        downloaded = $downloaded
        path = Get-RelativeProjectPath -Path $Destination
        bytes = $ExpectedBytes
    }
}

function Copy-ZipEntryChecked {
    param(
        [Parameter(Mandatory)][System.IO.Compression.ZipArchiveEntry]$Entry,
        [Parameter(Mandatory)][string]$Destination
    )

    $destinationDirectory = Split-Path -Parent $Destination
    New-Item -Path $destinationDirectory -ItemType Directory -Force | Out-Null
    $partialPath = "$Destination.partial"
    if (Test-Path -LiteralPath $partialPath) {
        Remove-Item -LiteralPath $partialPath -Force
    }

    $completed = $false
    try {
        $inputStream = $Entry.Open()
        $outputStream = [System.IO.FileStream]::new(
            $partialPath,
            [System.IO.FileMode]::CreateNew,
            [System.IO.FileAccess]::Write,
            [System.IO.FileShare]::None
        )
        try {
            $inputStream.CopyTo($outputStream)
            $outputStream.Flush($true)
        }
        finally {
            $outputStream.Dispose()
            $inputStream.Dispose()
        }

        Assert-FileLength -Path $partialPath -ExpectedBytes $Entry.Length
        Move-Item -LiteralPath $partialPath -Destination $Destination -Force
        $completed = $true
    }
    finally {
        if (-not $completed -and (Test-Path -LiteralPath $partialPath)) {
            Remove-Item -LiteralPath $partialPath -Force
        }
    }
}

function Expand-AssistmentsMirror {
    param(
        [Parameter(Mandatory)][string]$ArchivePath,
        [Parameter(Mandatory)][string]$Destination
    )

    $archive = [System.IO.Compression.ZipFile]::OpenRead($ArchivePath)
    try {
        $fileEntries = @($archive.Entries | Where-Object { -not $_.FullName.EndsWith('/') })
        if ($fileEntries.Count -ne 1) {
            throw "ASSISTments mirror ZIP must contain exactly one file; found $($fileEntries.Count)."
        }

        $entry = $fileEntries[0]
        if ($entry.FullName -ne 'skill_builder_data_corrected.csv') {
            throw "Unexpected ASSISTments mirror ZIP entry '$($entry.FullName)'."
        }
        if ($entry.Length -ne $sources.AssistmentsMirror.ExpectedEntryBytes) {
            throw "Unexpected ASSISTments mirror entry length $($entry.Length)."
        }
        if ($entry.CompressedLength -ne $sources.AssistmentsMirror.ExpectedEntryCompressedBytes) {
            throw "Unexpected ASSISTments mirror compressed length $($entry.CompressedLength)."
        }

        $crc32 = ('{0:X8}' -f $entry.Crc32)
        if ($crc32 -ne $sources.AssistmentsMirror.ExpectedEntryCrc32) {
            throw "Unexpected ASSISTments mirror CRC32 '$crc32'."
        }

        Copy-ZipEntryChecked -Entry $entry -Destination $Destination
    }
    finally {
        $archive.Dispose()
    }

    Assert-AssistmentsHeaderPrefix -Path $Destination
}

function Expand-OuladArchive {
    param(
        [Parameter(Mandatory)][string]$ArchivePath,
        [Parameter(Mandatory)][string]$DestinationDirectory
    )

    New-Item -Path $DestinationDirectory -ItemType Directory -Force | Out-Null
    $unexpectedExistingItems = @(
        Get-ChildItem -LiteralPath $DestinationDirectory -Force -ErrorAction SilentlyContinue |
            Where-Object { $_.PSIsContainer -or $expectedOuladEntries -notcontains $_.Name }
    )
    if ($unexpectedExistingItems.Count -gt 0) {
        throw "OULAD extraction directory contains unexpected items."
    }

    $archive = [System.IO.Compression.ZipFile]::OpenRead($ArchivePath)
    try {
        $fileEntries = @($archive.Entries | Where-Object { -not $_.FullName.EndsWith('/') })
        $actualNames = [string[]]@($fileEntries | ForEach-Object { $_.FullName } | Sort-Object)
        $expectedNames = [string[]]@($expectedOuladEntries | Sort-Object)

        if ($actualNames.Count -ne $expectedNames.Count) {
            throw "OULAD ZIP entry count mismatch: expected $($expectedNames.Count), got $($actualNames.Count)."
        }

        for ($index = 0; $index -lt $expectedNames.Count; $index++) {
            if ($actualNames[$index] -ne $expectedNames[$index]) {
                throw "Unexpected OULAD ZIP entry '$($actualNames[$index])'."
            }
        }

        foreach ($entry in $fileEntries) {
            if (
                $entry.FullName.Contains('/') -or
                $entry.FullName.Contains('\') -or
                $entry.Length -le 0
            ) {
                throw "Unsafe or empty OULAD ZIP entry '$($entry.FullName)'."
            }

            Copy-ZipEntryChecked -Entry $entry -Destination (
                Join-Path $DestinationDirectory $entry.FullName
            )
        }
    }
    finally {
        $archive.Dispose()
    }
}

function New-CsvParser {
    param([Parameter(Mandatory)][string]$Path)

    $parser = [Microsoft.VisualBasic.FileIO.TextFieldParser]::new($Path, $latin1, $true)
    $parser.TextFieldType = [Microsoft.VisualBasic.FileIO.FieldType]::Delimited
    $parser.SetDelimiters([string[]]@(','))
    $parser.HasFieldsEnclosedInQuotes = $true
    $parser.TrimWhiteSpace = $false
    return $parser
}

function Get-RecordDifferenceIndex {
    param(
        [Parameter(Mandatory)][AllowEmptyString()][string[]]$Left,
        [Parameter(Mandatory)][AllowEmptyString()][string[]]$Right
    )

    if ($Left.Count -ne $Right.Count) {
        return [Math]::Min($Left.Count, $Right.Count)
    }

    for ($index = 0; $index -lt $Left.Count; $index++) {
        if (-not [string]::Equals($Left[$index], $Right[$index], [System.StringComparison]::Ordinal)) {
            return $index
        }
    }

    return -1
}

function Get-FramedRecordKey {
    param([Parameter(Mandatory)][AllowEmptyString()][string[]]$Fields)

    $builder = [System.Text.StringBuilder]::new()
    [void]$builder.Append($Fields.Count).Append('|')
    foreach ($field in $Fields) {
        $value = [string]$field
        [void]$builder.Append($value.Length).Append(':').Append($value)
    }
    return $builder.ToString()
}

function Add-SemanticRecord {
    param(
        [Parameter(Mandatory)][System.Security.Cryptography.IncrementalHash]$Hasher,
        [Parameter(Mandatory)][AllowEmptyString()][string[]]$Fields
    )

    $fieldCountBytes = [System.BitConverter]::GetBytes(
        [System.Net.IPAddress]::HostToNetworkOrder([int]$Fields.Count)
    )
    $Hasher.AppendData($fieldCountBytes)

    foreach ($field in $Fields) {
        $valueBytes = $utf8NoBom.GetBytes([string]$field)
        $lengthBytes = [System.BitConverter]::GetBytes(
            [System.Net.IPAddress]::HostToNetworkOrder([int]$valueBytes.Length)
        )
        $Hasher.AppendData($lengthBytes)
        if ($valueBytes.Length -gt 0) {
            $Hasher.AppendData($valueBytes)
        }
    }
}

function ConvertTo-DeterministicCsvRecord {
    param([Parameter(Mandatory)][AllowEmptyString()][string[]]$Fields)

    $builder = [System.Text.StringBuilder]::new()
    for ($index = 0; $index -lt $Fields.Count; $index++) {
        if ($index -gt 0) {
            [void]$builder.Append(',')
        }

        $value = [string]$Fields[$index]
        $requiresQuotes = $value.IndexOfAny($csvSpecialCharacters) -ge 0
        if (
            $value.Length -gt 0 -and
            ([char]::IsWhiteSpace($value[0]) -or [char]::IsWhiteSpace($value[$value.Length - 1]))
        ) {
            $requiresQuotes = $true
        }

        if ($requiresQuotes) {
            [void]$builder.Append('"').Append($value.Replace('"', '""')).Append('"')
        }
        else {
            [void]$builder.Append($value)
        }
    }

    return $builder.ToString()
}

function Invoke-AssistmentsCanonicalization {
    param(
        [Parameter(Mandatory)][string]$OfficialPath,
        [Parameter(Mandatory)][string]$MirrorPath,
        [Parameter(Mandatory)][string]$CanonicalPath
    )

    if (-not (Test-Path -LiteralPath $canonicalizerPath -PathType Leaf)) {
        throw "ASSISTments canonicalizer not found: '$canonicalizerPath'."
    }
    $output = @(
        & java $canonicalizerPath `
            --official $OfficialPath `
            --mirror $MirrorPath `
            --canonical $CanonicalPath 2>&1
    )
    if ($LASTEXITCODE -ne 0) {
        throw "ASSISTments canonicalization failed: $($output -join [Environment]::NewLine)"
    }
    try {
        $result = ($output -join [Environment]::NewLine) | ConvertFrom-Json -AsHashtable
    }
    catch {
        throw "ASSISTments canonicalizer returned invalid JSON: $($output -join ' ')"
    }
    $result['canonical_path'] = Get-RelativeProjectPath -Path $CanonicalPath
    return [pscustomobject]$result

    $canonicalDirectory = Split-Path -Parent $CanonicalPath
    New-Item -Path $canonicalDirectory -ItemType Directory -Force | Out-Null
    $partialPath = "$CanonicalPath.partial"
    if (Test-Path -LiteralPath $partialPath) {
        Remove-Item -LiteralPath $partialPath -Force
    }

    $officialParser = $null
    $mirrorParser = $null
    $writer = $null
    $officialHasher = $null
    $mirrorHasher = $null
    $completed = $false

    try {
        $officialParser = New-CsvParser -Path $OfficialPath
        $mirrorParser = New-CsvParser -Path $MirrorPath
        $officialHeader = [string[]]$officialParser.ReadFields()
        $mirrorHeader = [string[]]$mirrorParser.ReadFields()

        $expectedDifference = Get-RecordDifferenceIndex -Left $expectedAssistmentsHeader -Right $officialHeader
        if ($expectedDifference -ne -1) {
            throw "Official ASSISTments CSV header differs at column $expectedDifference."
        }

        $mirrorDifference = Get-RecordDifferenceIndex -Left $officialHeader -Right $mirrorHeader
        if ($mirrorDifference -ne -1) {
            throw "ASSISTments mirror header differs at column $mirrorDifference."
        }

        $writer = [System.IO.StreamWriter]::new($partialPath, $false, $utf8NoBom)
        $writer.NewLine = "`n"
        $writer.WriteLine((ConvertTo-DeterministicCsvRecord -Fields $officialHeader))

        $officialHasher = [System.Security.Cryptography.IncrementalHash]::CreateHash(
            [System.Security.Cryptography.HashAlgorithmName]::SHA256
        )
        $mirrorHasher = [System.Security.Cryptography.IncrementalHash]::CreateHash(
            [System.Security.Cryptography.HashAlgorithmName]::SHA256
        )
        Add-SemanticRecord -Hasher $officialHasher -Fields $officialHeader
        Add-SemanticRecord -Hasher $mirrorHasher -Fields $mirrorHeader

        $seenRows = [System.Collections.Generic.HashSet[string]]::new(
            [System.StringComparer]::Ordinal
        )
        $eventCoreByOrderId = [System.Collections.Generic.Dictionary[string, string]]::new(
            [System.StringComparer]::Ordinal
        )
        $knowledgeFieldIndexes = [System.Collections.Generic.HashSet[int]]::new()
        foreach ($index in @(16, 17, 28, 29)) {
            [void]$knowledgeFieldIndexes.Add($index)
        }

        $officialRows = 0
        $canonicalRows = 0
        $mirrorRows = 0

        while (-not $officialParser.EndOfData) {
            $officialFields = [string[]]$officialParser.ReadFields()
            $officialRows++
            if ($officialFields.Count -ne $expectedAssistmentsHeader.Count) {
                throw "Official ASSISTments row $officialRows has $($officialFields.Count) fields."
            }

            $recordKey = Get-FramedRecordKey -Fields $officialFields
            if (-not $seenRows.Add($recordKey)) {
                continue
            }

            $canonicalRows++
            $orderId = $officialFields[0]
            if ([string]::IsNullOrWhiteSpace($orderId)) {
                throw "Official ASSISTments row $officialRows has an empty order_id."
            }

            $coreFields = [System.Collections.Generic.List[string]]::new()
            for ($index = 0; $index -lt $officialFields.Count; $index++) {
                if (-not $knowledgeFieldIndexes.Contains($index)) {
                    $coreFields.Add($officialFields[$index])
                }
            }
            $coreKey = Get-FramedRecordKey -Fields $coreFields.ToArray()
            $existingCore = $null
            if ($eventCoreByOrderId.TryGetValue($orderId, [ref]$existingCore)) {
                if (-not [string]::Equals($existingCore, $coreKey, [System.StringComparison]::Ordinal)) {
                    throw "Core-field conflict for ASSISTments order_id '$orderId'."
                }
            }
            else {
                $eventCoreByOrderId.Add($orderId, $coreKey)
            }

            if ($mirrorParser.EndOfData) {
                throw "ASSISTments mirror ended before canonical row $canonicalRows."
            }

            $mirrorFields = [string[]]$mirrorParser.ReadFields()
            $mirrorRows++
            $differenceIndex = Get-RecordDifferenceIndex -Left $officialFields -Right $mirrorFields
            if ($differenceIndex -ne -1) {
                $columnName = if ($differenceIndex -lt $expectedAssistmentsHeader.Count) {
                    $expectedAssistmentsHeader[$differenceIndex]
                }
                else {
                    'field-count'
                }
                throw "ASSISTments mirror differs at canonical row $canonicalRows, column '$columnName'."
            }

            Add-SemanticRecord -Hasher $officialHasher -Fields $officialFields
            Add-SemanticRecord -Hasher $mirrorHasher -Fields $mirrorFields
            $writer.WriteLine((ConvertTo-DeterministicCsvRecord -Fields $officialFields))
        }

        if (-not $mirrorParser.EndOfData) {
            [void]$mirrorParser.ReadFields()
            throw 'ASSISTments mirror contains rows after the canonical stream ended.'
        }

        $removedRows = $officialRows - $canonicalRows
        if ($officialRows -ne $sources.AssistmentsOfficial.ExpectedRows) {
            throw "Official ASSISTments row count mismatch: expected $($sources.AssistmentsOfficial.ExpectedRows), got $officialRows."
        }
        if ($canonicalRows -ne $sources.AssistmentsCanonical.ExpectedRows) {
            throw "Canonical ASSISTments row count mismatch: expected $($sources.AssistmentsCanonical.ExpectedRows), got $canonicalRows."
        }
        if ($mirrorRows -ne $sources.AssistmentsMirror.ExpectedRows) {
            throw "ASSISTments mirror row count mismatch: expected $($sources.AssistmentsMirror.ExpectedRows), got $mirrorRows."
        }
        if ($removedRows -ne $sources.AssistmentsCanonical.ExpectedRemovedRows) {
            throw "ASSISTments duplicate count mismatch: expected $($sources.AssistmentsCanonical.ExpectedRemovedRows), got $removedRows."
        }
        if ($eventCoreByOrderId.Count -ne $sources.AssistmentsCanonical.ExpectedUniqueOrderIds) {
            throw "ASSISTments unique order_id count mismatch: expected $($sources.AssistmentsCanonical.ExpectedUniqueOrderIds), got $($eventCoreByOrderId.Count)."
        }

        $officialSemanticHash = [System.Convert]::ToHexString(
            $officialHasher.GetHashAndReset()
        ).ToLowerInvariant()
        $mirrorSemanticHash = [System.Convert]::ToHexString(
            $mirrorHasher.GetHashAndReset()
        ).ToLowerInvariant()
        if ($officialSemanticHash -ne $mirrorSemanticHash) {
            throw 'ASSISTments normalized semantic SHA-256 mismatch.'
        }

        $writer.Flush()
        $writer.Dispose()
        $writer = $null
        Move-Item -LiteralPath $partialPath -Destination $CanonicalPath -Force
        $completed = $true

        return [pscustomobject][ordered]@{
            official_rows = $officialRows
            exact_duplicate_rows_removed = $removedRows
            canonical_rows = $canonicalRows
            mirror_rows = $mirrorRows
            unique_order_ids = $eventCoreByOrderId.Count
            normalized_semantic_sha256 = $officialSemanticHash
            canonical_sha256 = Get-LowerFileHash -Path $CanonicalPath -Algorithm SHA256
            canonical_bytes = (Get-Item -LiteralPath $CanonicalPath).Length
            canonical_path = Get-RelativeProjectPath -Path $CanonicalPath
        }
    }
    finally {
        if ($null -ne $writer) {
            $writer.Dispose()
        }
        if ($null -ne $officialParser) {
            $officialParser.Dispose()
        }
        if ($null -ne $mirrorParser) {
            $mirrorParser.Dispose()
        }
        if ($null -ne $officialHasher) {
            $officialHasher.Dispose()
        }
        if ($null -ne $mirrorHasher) {
            $mirrorHasher.Dispose()
        }
        if (-not $completed -and (Test-Path -LiteralPath $partialPath)) {
            Remove-Item -LiteralPath $partialPath -Force
        }
    }
}

function Get-FileLockEntry {
    param([Parameter(Mandatory)][string]$Path)

    return [ordered]@{
        path = Get-RelativeProjectPath -Path $Path
        bytes = (Get-Item -LiteralPath $Path).Length
        sha256 = Get-LowerFileHash -Path $Path -Algorithm SHA256
    }
}

function Write-SourceLock {
    param(
        [Parameter(Mandatory)]$OfficialDownload,
        [Parameter(Mandatory)]$MirrorDownload,
        [Parameter(Mandatory)]$OuladDownload,
        [Parameter(Mandatory)]$Canonicalization
    )

    $ouladFiles = @(
        foreach ($name in ($expectedOuladEntries | Sort-Object)) {
            Get-FileLockEntry -Path (Join-Path $sources.Oulad.ExtractedDirectory $name)
        }
    )

    $ouladArchiveLock = Get-FileLockEntry -Path $sources.Oulad.Archive
    $ouladArchiveLock['md5'] = Get-LowerFileHash -Path $sources.Oulad.Archive -Algorithm MD5

    $previousLock = $null
    if (Test-Path -LiteralPath $lockPath -PathType Leaf) {
        try {
            $previousLock = Get-Content -LiteralPath $lockPath -Raw | ConvertFrom-Json
        }
        catch {
            $previousLock = $null
        }
    }
    $generatedAtUtc = if ($null -ne $previousLock -and -not [string]::IsNullOrWhiteSpace([string]$previousLock.generated_at_utc)) {
        [string]$previousLock.generated_at_utc
    }
    else {
        [System.DateTime]::UtcNow.ToString('o')
    }
    $lock = [ordered]@{
        schema_version = 2
        generated_at_utc = $generatedAtUtc
        source_manifest = [ordered]@{
            path = Get-RelativeProjectPath -Path $manifestPath
            bytes = (Get-Item -LiteralPath $manifestPath).Length
            sha256 = Get-LowerFileHash -Path $manifestPath -Algorithm SHA256
        }
        generator = [ordered]@{
            path = Get-RelativeProjectPath -Path $scriptPath
            bytes = (Get-Item -LiteralPath $scriptPath).Length
            sha256 = Get-LowerFileHash -Path $scriptPath -Algorithm SHA256
            powershell = $PSVersionTable.PSVersion.ToString()
            canonicalizer = Get-FileLockEntry -Path $canonicalizerPath
            java = (& java -version 2>&1 | Select-Object -First 1).ToString()
        }
        assistments_2009_2010_skill_builder = [ordered]@{
            official_raw_nonfolded = [ordered]@{
                source_landing_url = 'https://sites.google.com/site/assistmentsdata/home/2009-2010-assistment-data/skill-builder-data-2009-2010'
                download_url_env = $assistmentsDownloadUrlVariable
                file = Get-FileLockEntry -Path $sources.AssistmentsOfficial.Destination
                expected_rows = $sources.AssistmentsOfficial.ExpectedRows
            }
            corrected_nonfolded_academic_mirror = [ordered]@{
                download_url = $MirrorDownload.requested_url
                archive = Get-FileLockEntry -Path $sources.AssistmentsMirror.Archive
                entry_crc32 = $sources.AssistmentsMirror.ExpectedEntryCrc32
                extracted = Get-FileLockEntry -Path $sources.AssistmentsMirror.Extracted
            }
            canonical_corrected_nonfolded = [ordered]@{
                file = [ordered]@{
                    path = $Canonicalization.canonical_path
                    bytes = $Canonicalization.canonical_bytes
                    sha256 = $Canonicalization.canonical_sha256
                }
                official_rows = $Canonicalization.official_rows
                official_repeated_lineage_rows = $Canonicalization.official_repeated_lineage_rows
                official_opportunity_variant_rows = $Canonicalization.official_opportunity_variant_rows
                rows = $Canonicalization.canonical_rows
                mirror_rows = $Canonicalization.mirror_rows
                unique_order_ids = $Canonicalization.unique_order_ids
                mirror_answer_text_mismatches = $Canonicalization.mirror_answer_text_mismatches
                mirror_retained_opportunity_mismatches = $Canonicalization.mirror_retained_opportunity_mismatches
                canonical_semantic_sha256 = $Canonicalization.canonical_semantic_sha256
                semantic_hash_excludes = @('answer_text')
                lineage_key_excludes = @('answer_text', 'opportunity', 'opportunity_original')
                answer_text_source = 'official_raw_nonfolded'
                official_mirror_lineage_match = $true
                retained_training_fields_match = $true
            }
        }
        oulad = [ordered]@{
            download_url = $OuladDownload.requested_url
            archive = $ouladArchiveLock
            license = 'CC-BY-4.0'
            dataset_doi = '10.6084/m9.figshare.5081998.v1'
            extracted_files = $ouladFiles
        }
        deployment_boundary = [ordered]@{
            raw_data_in_git = $false
            raw_data_in_images = $false
            raw_data_on_server = $false
        }
    }

    $lockDirectory = Split-Path -Parent $lockPath
    New-Item -Path $lockDirectory -ItemType Directory -Force | Out-Null
    $partialPath = "$lockPath.partial"
    if (Test-Path -LiteralPath $partialPath) {
        Remove-Item -LiteralPath $partialPath -Force
    }

    $json = $lock | ConvertTo-Json -Depth 12
    [System.IO.File]::WriteAllText($partialPath, $json + "`n", $utf8NoBom)
    Move-Item -LiteralPath $partialPath -Destination $lockPath -Force
}

if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    throw "Source manifest not found: '$manifestPath'."
}

Write-Stage 'Downloading and validating official ASSISTments raw data.'
$officialDownloadParameters = @{
    Url = $sources.AssistmentsOfficial.Url
    Destination = $sources.AssistmentsOfficial.Destination
    ExpectedBytes = $sources.AssistmentsOfficial.ExpectedBytes
    Kind = 'csv'
    AcceptedContentTypes = @('application/octet-stream', 'text/csv')
}
$officialDownload = Invoke-CheckedDownload @officialDownloadParameters

Write-Stage 'Downloading and validating the USTC corrected non-folded mirror.'
$mirrorDownloadParameters = @{
    Url = $sources.AssistmentsMirror.Url
    Destination = $sources.AssistmentsMirror.Archive
    ExpectedBytes = $sources.AssistmentsMirror.ExpectedBytes
    Kind = 'zip'
    AcceptedContentTypes = @('application/zip', 'application/octet-stream')
}
$mirrorDownload = Invoke-CheckedDownload @mirrorDownloadParameters
$mirrorExpansionParameters = @{
    ArchivePath = $sources.AssistmentsMirror.Archive
    Destination = $sources.AssistmentsMirror.Extracted
}
Expand-AssistmentsMirror @mirrorExpansionParameters

Write-Stage 'Deriving canonical ASSISTments data and comparing the mirror.'
$canonicalizationParameters = @{
    OfficialPath = $sources.AssistmentsOfficial.Destination
    MirrorPath = $sources.AssistmentsMirror.Extracted
    CanonicalPath = $sources.AssistmentsCanonical.Destination
}
$canonicalization = Invoke-AssistmentsCanonicalization @canonicalizationParameters

Write-Stage 'Downloading and validating the OULAD Figshare archive.'
$ouladDownloadParameters = @{
    Url = $sources.Oulad.Url
    Destination = $sources.Oulad.Archive
    ExpectedBytes = $sources.Oulad.ExpectedBytes
    Kind = 'zip'
    AcceptedContentTypes = @('application/zip', 'application/octet-stream', 'binary/octet-stream')
    ExpectedMd5 = $sources.Oulad.ExpectedMd5
}
$ouladDownload = Invoke-CheckedDownload @ouladDownloadParameters
$ouladExpansionParameters = @{
    ArchivePath = $sources.Oulad.Archive
    DestinationDirectory = $sources.Oulad.ExtractedDirectory
}
Expand-OuladArchive @ouladExpansionParameters

Write-Stage 'Writing the source lock.'
$sourceLockParameters = @{
    OfficialDownload = $officialDownload
    MirrorDownload = $mirrorDownload
    OuladDownload = $ouladDownload
    Canonicalization = $canonicalization
}
Write-SourceLock @sourceLockParameters

Write-Stage "Source lock written to '$(Get-RelativeProjectPath -Path $lockPath)'."
