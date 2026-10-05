# Starts only this project's local database and the already-built Java backend.
# Loads whitelisted paths/Qwen settings. Does not build/test, scan files, or enable Redis.
[CmdletBinding()]
param([switch]$DisableCopilot)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'local-common.ps1')
. (Join-Path $PSScriptRoot 'local-runtime.ps1')
Assert-DayuTools
$settings = Get-DayuSettings
$runtime = Get-DayuRuntime -DisableCopilot:$DisableCopilot
$repo = $DayuRepo
$credentials = Join-Path $repo '.env.local-db'
$compose = Join-Path $repo 'infra/compose.local.yml'
$jar = Join-Path $repo 'backend/target/dayu-platform-backend-0.0.1-SNAPSHOT.jar'
if (!(Test-Path -LiteralPath $credentials) -or !(Test-Path -LiteralPath $jar)) {
    throw 'Missing .env.local-db or built backend JAR. This script does not rebuild or reset credentials.'
}
if (Get-NetTCPConnection -State Listen -LocalPort $settings.HttpPort -ErrorAction SilentlyContinue) {
    throw "Port $($settings.HttpPort) is already in use. No existing process will be stopped."
}
$password = $null
foreach ($line in [System.IO.File]::ReadAllLines($credentials)) {
    if ($line -match '^DAYU_LOCAL_DB_PASSWORD=(.*)$') { $password = $matches[1].Trim() }
}
if ([string]::IsNullOrWhiteSpace($password)) { throw 'Local database password is missing.' }

# Explicit --env-file prevents Docker Compose from loading the Qwen .env file.
Invoke-DayuCompose @('up','-d','--wait','--wait-timeout','180')

$logDir = Join-Path $repo 'logs'
New-Item -ItemType Directory -Path $logDir -Force | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$stdout = Join-Path $logDir "backend-local-$stamp.out.log"
$stderr = Join-Path $logDir "backend-local-$stamp.err.log"
$java = (Get-DayuJdk).Java

# Secrets are passed only through the child environment, never a command-line argument.
# Start-Process -Environment is required here: mutating the parent process environment through
# System.Environment does not reliably populate the child block in current PowerShell releases.
$overrides = @{
    SPRING_DATASOURCE_PASSWORD = $password
    SPRING_APPLICATION_JSON = $runtime.Json
    SPRING_CONFIG_ADDITIONAL_LOCATION = $null
    DASHSCOPE_API_KEY = $runtime.ApiKey
    DAYU_COPILOT_QWEN_API_KEY = $null
}
try {
    $arguments = @('-Xmx384m', '-jar', ('"{0}"' -f $jar), '--debug=false',
        '--spring.config.location=classpath:/application.yml', '--spring.profiles.active=local',
        "--spring.datasource.url=jdbc:mariadb://127.0.0.1:$($settings.DbPort)/dayu", '--spring.datasource.username=dayu',
        '--spring.flyway.enabled=true', '--server.address=127.0.0.1', "--server.port=$($settings.HttpPort)",
        '--dayu.indexing.enabled=false', '--dayu.cache.enabled=false')
    $backend = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $repo `
        -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr `
        -Environment $overrides -PassThru
    New-Item -ItemType Directory -Path (Split-Path -Parent $settings.StateFile) -Force | Out-Null
    @{Pid=$backend.Id;StartedAt=$backend.StartTime.ToUniversalTime().ToString('o');Jar=$jar;Port=$settings.HttpPort} |
        ConvertTo-Json | Set-Content -LiteralPath $settings.StateFile -Encoding UTF8
} finally {
    $password = $null
    $runtime.ApiKey = $null
    $overrides.Clear()
}

$ready = $false
$timer = [Diagnostics.Stopwatch]::StartNew()
while ($timer.Elapsed.TotalSeconds -lt 180 -and !$backend.HasExited) {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri ($settings.BaseUrl+'/api/v1/products') -TimeoutSec 2
        if ($response.StatusCode -eq 200) { $ready = $true; break }
    } catch { Start-Sleep -Seconds 1 }
}
if (!$ready) { throw "Backend did not become ready. Inspect $stdout and $stderr. No process was killed." }
Write-Output "Backend ready: $($settings.BaseUrl)/api/v1/products"
Write-Output "Backend PID: $($backend.Id)"
Write-Output "MariaDB: 127.0.0.1:$($settings.DbPort) / database=dayu / user=dayu"
Write-Output "Password file (not printed): $credentials"
Write-Output "Startup log: $stdout"
Write-Output "Copilot enabled: $($runtime.CopilotEnabled). Startup does not send a model request."
