[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$BaseUrl,
    [Parameter(Mandatory)][string]$EnvFile,
    [Parameter(Mandatory)][string]$OutputPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
. (Join-Path $repositoryRoot 'tests\support\Acceptance.Http.ps1')
$environment = Read-AcceptanceEnvironment -Path $EnvFile
$teacherPassword = Get-RequiredEnvironmentValue `
    -Environment $environment `
    -Name 'EDUTWIN_DEMO_TEACHER_PASSWORD'
$teacherRecord = Get-DemoTeacherRecord -RepositoryRoot $repositoryRoot
$studentPassword = Get-RequiredEnvironmentValue `
    -Environment $environment `
    -Name 'EDUTWIN_DEMO_STUDENT_PASSWORD'
$adminUsername = Get-RequiredEnvironmentValue -Environment $environment -Name 'EDUTWIN_ADMIN_USERNAME'
$adminPassword = Get-RequiredEnvironmentValue -Environment $environment -Name 'EDUTWIN_ADMIN_PASSWORD'
$checks = [System.Collections.Generic.List[object]]::new()

function Assert-No-TechnicalTransparency {
    param([Parameter(Mandatory)][object]$Value, [Parameter(Mandatory)][string]$Context)
    $json = $Value | ConvertTo-Json -Depth 40 -Compress
    $forbidden = @('trace', 'snapshotId', 'dataVersions', 'modelVersions', 'modelVersion',
        'artifactSha256', 'manifestSha256', 'degraded', 'degradationReasons',
        'providerModel', 'generationMode', 'toolCallVerified')
    foreach ($name in $forbidden) {
        Assert-AcceptanceCondition -Condition ($json -notmatch ('"' + [regex]::Escape($name) + '"\s*:')) `
            -Message "$Context exposes forbidden technical field $name."
    }
}

function Add-Check {
    param([string]$Name, [int]$StatusCode, [int64]$ElapsedMs)
    $checks.Add([ordered]@{ name = $Name; statusCode = $StatusCode; elapsedMs = $ElapsedMs })
}

$anonymous = Invoke-AcceptanceHttp `
    -BaseUrl $BaseUrl `
    -Path '/api/v1/courses' `
    -ExpectedStatus @(401)
Add-Check -Name 'anonymous-courses-denied' -StatusCode $anonymous.StatusCode -ElapsedMs $anonymous.ElapsedMs

$teacher = Connect-EduTwinUser `
    -BaseUrl $BaseUrl `
    -Username ([string]$teacherRecord.username) `
    -Password $teacherPassword
Assert-AcceptanceCondition `
    -Condition ([string]$teacher.user.role -eq 'TEACHER') `
    -Message 'Teacher login returned a non-teacher role.'
$teacherToken = [string]$teacher.accessToken

$me = Invoke-AcceptanceHttp -BaseUrl $BaseUrl -Path '/api/v1/auth/me' -Token $teacherToken
Assert-AcceptanceProperties -Value $me.Body -Names @('userId', 'username', 'role', 'accessibleCourseIds') -Context 'Authenticated teacher'
Add-Check -Name 'teacher-me' -StatusCode $me.StatusCode -ElapsedMs $me.ElapsedMs

$courses = Invoke-AcceptanceHttp -BaseUrl $BaseUrl -Path '/api/v1/courses' -Token $teacherToken
Assert-AcceptanceCondition -Condition (@($courses.Body.items).Count -gt 0) -Message 'Teacher course list is empty.'
$courseId = [string]$courses.Body.items[0].courseId
Add-Check -Name 'teacher-courses' -StatusCode $courses.StatusCode -ElapsedMs $courses.ElapsedMs

$dashboard = Invoke-AcceptanceHttp `
    -BaseUrl $BaseUrl `
    -Path "/api/v1/teacher/courses/$courseId/dashboard" `
    -Token $teacherToken
Assert-AcceptanceProperties `
    -Value $dashboard.Body `
    -Names @('courseId', 'studentCount', 'averageRiskProbability', 'students') `
    -Context 'Teacher dashboard'
Assert-No-TechnicalTransparency -Value $dashboard.Body -Context 'Teacher dashboard'
Assert-AcceptanceCondition -Condition (@($dashboard.Body.students).Count -gt 1) -Message 'Teacher dashboard lacks testable student rows.'
Add-Check -Name 'teacher-dashboard' -StatusCode $dashboard.StatusCode -ElapsedMs $dashboard.ElapsedMs

$studentId = [string]$dashboard.Body.students[0].studentId
$otherStudentId = [string]$dashboard.Body.students[1].studentId
$studentRecord = Get-DemoStudentRecord -RepositoryRoot $repositoryRoot -StudentId $studentId
$student = Connect-EduTwinUser `
    -BaseUrl $BaseUrl `
    -Username ([string]$studentRecord.username) `
    -Password $studentPassword
Assert-AcceptanceCondition -Condition ([string]$student.user.role -eq 'STUDENT') -Message 'Student login returned a non-student role.'
$studentToken = [string]$student.accessToken

$selfTwin = Invoke-AcceptanceHttp `
    -BaseUrl $BaseUrl `
    -Path "/api/v1/courses/$courseId/students/$studentId/twin/current" `
    -Token $studentToken
Assert-AcceptanceProperties `
    -Value $selfTwin.Body `
    -Names @('snapshotVersion', 'mastery', 'nextCorrectProbability', 'risk') `
    -Context 'Student twin'
Assert-No-TechnicalTransparency -Value $selfTwin.Body -Context 'Student twin'
Add-Check -Name 'student-self-twin' -StatusCode $selfTwin.StatusCode -ElapsedMs $selfTwin.ElapsedMs

$history = Invoke-AcceptanceHttp `
    -BaseUrl $BaseUrl `
    -Path "/api/v1/courses/$courseId/students/$studentId/twin/history?limit=5" `
    -Token $studentToken
Assert-AcceptanceCondition -Condition (@($history.Body.items).Count -gt 0) -Message 'Student twin history is empty.'
Add-Check -Name 'student-twin-history' -StatusCode $history.StatusCode -ElapsedMs $history.ElapsedMs

$plan = Invoke-AcceptanceHttp `
    -BaseUrl $BaseUrl `
    -Path "/api/v1/courses/$courseId/students/$studentId/learning-plans/current" `
    -Token $studentToken
Assert-AcceptanceProperties -Value $plan.Body -Names @('planId', 'version', 'tasks') -Context 'Learning plan'
Assert-No-TechnicalTransparency -Value $plan.Body -Context 'Learning plan'
Assert-AcceptanceCondition -Condition (@($plan.Body.tasks).Count -gt 0) -Message 'Current learning plan contains no tasks.'
Add-Check -Name 'student-current-plan' -StatusCode $plan.StatusCode -ElapsedMs $plan.ElapsedMs

$practice = Invoke-AcceptanceHttp `
    -BaseUrl $BaseUrl `
    -Path "/api/v1/courses/$courseId/practice/next" `
    -Token $studentToken
Assert-AcceptanceProperties -Value $practice.Body -Names @('questionId', 'choices', 'skillIds') -Context 'Practice question'
Assert-No-TechnicalTransparency -Value $practice.Body -Context 'Practice question'
Add-Check -Name 'student-practice-next' -StatusCode $practice.StatusCode -ElapsedMs $practice.ElapsedMs

$studentTransparency = Invoke-AcceptanceHttp -BaseUrl $BaseUrl -Path '/api/v1/admin/transparency' `
    -Token $studentToken -ExpectedStatus @(403)
Add-Check -Name 'student-transparency-denied' -StatusCode $studentTransparency.StatusCode -ElapsedMs $studentTransparency.ElapsedMs

$admin = Connect-EduTwinUser -BaseUrl $BaseUrl -Username $adminUsername -Password $adminPassword
Assert-AcceptanceCondition -Condition ([string]$admin.user.role -eq 'ADMIN') -Message 'Admin login returned a non-admin role.'
$transparency = Invoke-AcceptanceHttp -BaseUrl $BaseUrl -Path '/api/v1/admin/transparency' -Token ([string]$admin.accessToken)
Assert-AcceptanceCondition -Condition (@($transparency.Body.provenance.sources).Count -eq 2) -Message 'Transparency response does not expose both data sources.'
Assert-AcceptanceCondition -Condition (@($transparency.Body.models).Count -eq 7) -Message 'Transparency response does not expose seven model candidates.'
Add-Check -Name 'admin-transparency' -StatusCode $transparency.StatusCode -ElapsedMs $transparency.ElapsedMs

$studentDashboard = Invoke-AcceptanceHttp `
    -BaseUrl $BaseUrl `
    -Path "/api/v1/teacher/courses/$courseId/dashboard" `
    -Token $studentToken `
    -ExpectedStatus @(403)
Add-Check -Name 'student-dashboard-denied' -StatusCode $studentDashboard.StatusCode -ElapsedMs $studentDashboard.ElapsedMs

$otherTwin = Invoke-AcceptanceHttp `
    -BaseUrl $BaseUrl `
    -Path "/api/v1/courses/$courseId/students/$otherStudentId/twin/current" `
    -Token $studentToken `
    -ExpectedStatus @(403)
Add-Check -Name 'student-other-twin-denied' -StatusCode $otherTwin.StatusCode -ElapsedMs $otherTwin.ElapsedMs

$teacherPractice = Invoke-AcceptanceHttp `
    -BaseUrl $BaseUrl `
    -Path "/api/v1/courses/$courseId/practice/next" `
    -Token $teacherToken `
    -ExpectedStatus @(403)
Add-Check -Name 'teacher-practice-denied' -StatusCode $teacherPractice.StatusCode -ElapsedMs $teacherPractice.ElapsedMs

$report = [ordered]@{
    schemaVersion = 1
    kind = 'edutwin-live-contract-verification'
    status = 'PASSED'
    baseUrl = $BaseUrl
    verifiedAt = [DateTimeOffset]::UtcNow.ToString('o')
    courseId = $courseId
    teacherUserId = [string]$teacher.user.userId
    studentId = $studentId
    checks = @($checks)
}
Write-AcceptanceEvidence -Value $report -Path $OutputPath
$report
