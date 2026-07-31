[CmdletBinding()]
param(
    [string]$Destination = (Join-Path $PSScriptRoot '..\..\infra\env\.env'),
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$resolvedDestination = [System.IO.Path]::GetFullPath($Destination)
if ((Test-Path -LiteralPath $resolvedDestination) -and -not $Force) {
    throw "Environment file already exists: '$resolvedDestination'."
}

function New-RandomSecret {
    param([int]$ByteCount = 32)

    return [System.Convert]::ToHexString(
        [System.Security.Cryptography.RandomNumberGenerator]::GetBytes($ByteCount)
    ).ToLowerInvariant()
}

function New-Base64Secret {
    param([int]$ByteCount = 32)

    return [System.Convert]::ToBase64String(
        [System.Security.Cryptography.RandomNumberGenerator]::GetBytes($ByteCount)
    )
}

$values = [ordered]@{
    COMPOSE_PROJECT_NAME = 'edutwin'
    EDUTWIN_IMAGE_TAG = 'local'
    EDUTWIN_VCS_REF = 'unknown'
    EDUTWIN_BACKEND_IMAGE = 'edutwin/backend'
    EDUTWIN_MODEL_IMAGE = 'edutwin/model-service'
    EDUTWIN_FRONTEND_IMAGE = 'edutwin/frontend'
    MYSQL_DATABASE = 'edutwin'
    MYSQL_USER = 'edutwin'
    MYSQL_PASSWORD = New-RandomSecret
    MYSQL_ROOT_PASSWORD = New-RandomSecret
    REDIS_PASSWORD = New-RandomSecret
    EDUTWIN_JWT_SECRET = New-RandomSecret -ByteCount 48
    EDUTWIN_ADMIN_USERNAME = 'admin'
    EDUTWIN_ADMIN_PASSWORD = New-RandomSecret
    EDUTWIN_ADMIN_DISPLAY_NAME = 'System Administrator'
    EDUTWIN_DEMO_IMPORT_ENABLED = 'true'
    EDUTWIN_DEMO_TEACHER_PASSWORD = New-RandomSecret
    EDUTWIN_DEMO_STUDENT_PASSWORD = New-RandomSecret
    EDUTWIN_AI_ENABLED = 'false'
    EDUTWIN_AI_CONFIG_ENCRYPTION_KEY = New-Base64Secret
    EDUTWIN_LLM_API_KEY = 'disabled'
    EDUTWIN_LLM_BASE_URL = 'https://api.deepseek.com'
    EDUTWIN_LLM_MODEL = 'deepseek-v4-flash'
    DEEPSEEK_API_KEY = 'disabled'
    DEEPSEEK_BASE_URL = 'https://api.deepseek.com'
    DEEPSEEK_MODEL = 'deepseek-v4-flash'
}

$lines = foreach ($entry in $values.GetEnumerator()) {
    '{0}={1}' -f $entry.Key, $entry.Value
}
$directory = Split-Path -Parent $resolvedDestination
New-Item -Path $directory -ItemType Directory -Force | Out-Null
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllText(
    $resolvedDestination,
    ([string]::Join("`n", $lines) + "`n"),
    $utf8NoBom
)

if ($IsWindows) {
    $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
    & icacls.exe $resolvedDestination /inheritance:r /grant:r "${identity}:(F)" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to restrict the environment file ACL."
    }
}
else {
    & chmod 600 $resolvedDestination
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to restrict the environment file mode."
    }
}

[pscustomobject]@{
    path = $resolvedDestination
    ai_enabled = $false
    deepseek_key_configured = $false
}
