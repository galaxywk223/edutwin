[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-GitGrep {
    param(
        [Parameter(Mandatory)][string]$Revision,
        [Parameter(Mandatory)][string]$Pattern
    )

    $output = @(& git grep -n -I -E -- $Pattern $Revision -- . 2>$null)
    if ($LASTEXITCODE -notin @(0, 1)) {
        throw "git grep failed for revision $Revision."
    }
    return @($output)
}

function Test-NonPublicIpv4 {
    param([Parameter(Mandatory)][System.Net.IPAddress]$Address)

    $bytes = $Address.GetAddressBytes()
    if ($bytes.Length -ne 4) { return $true }
    if ([System.Net.IPAddress]::IsLoopback($Address)) { return $true }
    if ($bytes[0] -in @(0, 10, 127)) { return $true }
    if ($bytes[0] -eq 100 -and $bytes[1] -ge 64 -and $bytes[1] -le 127) { return $true }
    if ($bytes[0] -eq 169 -and $bytes[1] -eq 254) { return $true }
    if ($bytes[0] -eq 172 -and $bytes[1] -ge 16 -and $bytes[1] -le 31) { return $true }
    if ($bytes[0] -eq 192 -and $bytes[1] -eq 168) { return $true }
    if ($bytes[0] -eq 192 -and $bytes[1] -eq 0 -and $bytes[2] -eq 2) { return $true }
    if ($bytes[0] -eq 198 -and $bytes[1] -in @(18, 19)) { return $true }
    if ($bytes[0] -eq 198 -and $bytes[1] -eq 51 -and $bytes[2] -eq 100) { return $true }
    if ($bytes[0] -eq 203 -and $bytes[1] -eq 0 -and $bytes[2] -eq 113) { return $true }
    if ($bytes[0] -ge 224) { return $true }
    return $false
}

$revisions = @(& git rev-list --all)
if ($LASTEXITCODE -ne 0 -or $revisions.Count -eq 0) {
    throw 'No reachable Git revisions were found.'
}

$literalPatterns = [ordered]@{
    capability_query = ('resource' + 'key=')
    privileged_ssh = ('root' + '@')
    windows_user_path = '[A-Za-z]:\\Users\\[^\\/:*?"<>|]+'
    windows_build = 'Windows-(10|11)-[0-9]+\.[0-9]+\.[0-9]+-SP[0-9]+'
}
$violations = [System.Collections.Generic.List[string]]::new()

foreach ($revision in $revisions) {
    foreach ($entry in $literalPatterns.GetEnumerator()) {
        foreach ($match in Invoke-GitGrep -Revision $revision -Pattern $entry.Value) {
            $violations.Add("$($entry.Key): $match")
        }
    }

    $networkPattern = '(https?://|Server[[:space:]]*=[[:space:]]*[''\"])([0-9]{1,3}\.){3}[0-9]{1,3}'
    foreach ($match in Invoke-GitGrep -Revision $revision -Pattern $networkPattern) {
        foreach ($candidate in [regex]::Matches($match, '(?<![0-9])([0-9]{1,3}\.){3}[0-9]{1,3}(?![0-9])')) {
            $address = $null
            if ([System.Net.IPAddress]::TryParse($candidate.Value, [ref]$address) -and
                -not (Test-NonPublicIpv4 -Address $address)) {
                $violations.Add("public_ipv4: $match")
            }
        }
    }
}

$commitIdentities = @(& git log --all --format='%H%x09%ae%x09%ce')
if ($LASTEXITCODE -ne 0) {
    throw 'Commit email inspection failed.'
}

$noreplyPattern = '(^noreply@github\.com$|@users\.noreply\.github\.com$)'
foreach ($identity in $commitIdentities) {
    $fields = @($identity -split "`t", 3)
    if ($fields.Count -ne 3) {
        throw "Unexpected commit identity record: $identity"
    }

    $commit = $fields[0]
    $authorEmail = $fields[1]
    $committerEmail = $fields[2]
    if ($committerEmail -notmatch $noreplyPattern) {
        $violations.Add("commit_committer_email: ${commit}: $committerEmail")
    }
    if ($authorEmail -notmatch $noreplyPattern -and $committerEmail -ne 'noreply@github.com') {
        $violations.Add("commit_author_email: ${commit}: $authorEmail")
    }
}

$emails = @($commitIdentities | ForEach-Object {
    $fields = @($_ -split "`t", 3)
    $fields[1]
    $fields[2]
} | Sort-Object -Unique)

if ($violations.Count -gt 0) {
    $violations | Sort-Object -Unique | ForEach-Object { Write-Error $_ }
    throw "Public history safety check found $($violations.Count) violation(s)."
}

[pscustomobject]@{
    status = 'PASSED'
    revisions = $revisions.Count
    commitEmails = $emails.Count
}
