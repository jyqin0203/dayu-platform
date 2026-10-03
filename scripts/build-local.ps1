# Build from source using the checked-in Maven wrapper. This command explicitly does not run tests.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'local-common.ps1')
$repo = $DayuRepo
$jdk = Get-DayuJdk
$previousJavaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME','Process')
$previousMaven = [Environment]::GetEnvironmentVariable('MAVEN_OPTS','Process')
try {
    $env:MAVEN_OPTS='-Xmx256m'
    $env:JAVA_HOME=$jdk.Home
    Push-Location (Join-Path $repo 'backend')
    try {
        & .\mvnw.cmd --batch-mode --no-transfer-progress '-DskipTests' package
        if ($LASTEXITCODE -ne 0) { throw 'Backend build failed. Do not start a previously built JAR.' }
    } finally { Pop-Location }
} finally {
    [Environment]::SetEnvironmentVariable('MAVEN_OPTS',$previousMaven,'Process')
    [Environment]::SetEnvironmentVariable('JAVA_HOME',$previousJavaHome,'Process')
}
Write-Output 'Build complete. Tests were NOT executed. Next: scripts/start-local.ps1.'
