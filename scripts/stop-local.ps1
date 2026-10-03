# Stop only the backend identified by this checkout's recorded PID/start time/JAR. Never delete database data.
[CmdletBinding()]
param([switch]$Database)
. (Join-Path $PSScriptRoot 'local-common.ps1')
$settings = Get-DayuSettings
if (Test-Path -LiteralPath $settings.StateFile) {
    $state = Get-Content -LiteralPath $settings.StateFile -Raw -Encoding UTF8 | ConvertFrom-Json
    $process = Get-Process -Id $state.Pid -ErrorAction SilentlyContinue
    if ($process) {
        $expectedJar = [IO.Path]::GetFullPath((Join-Path $DayuRepo 'backend/target/dayu-platform-backend-0.0.1-SNAPSHOT.jar'))
        $details = Get-CimInstance Win32_Process -Filter "ProcessId=$([int]$state.Pid)"
        if ($state.Jar -ne $expectedJar -or $state.Port -ne $settings.HttpPort -or
            $process.StartTime.ToUniversalTime().ToString('o') -ne $state.StartedAt -or
            !$details.CommandLine.Contains($expectedJar) -or
            !$details.CommandLine.Contains("--server.port=$($settings.HttpPort)")) {
            throw 'Recorded process identity does not match. Nothing was stopped.'
        }
        Stop-Process -Id $process.Id -ErrorAction Stop
        $process.WaitForExit(10000) | Out-Null
        Write-Output "Stopped this checkout's backend PID $($state.Pid)."
    } else { Write-Output 'Recorded backend is already stopped.' }
} elseif (Get-NetTCPConnection -State Listen -LocalPort $settings.HttpPort -ErrorAction SilentlyContinue) {
    throw 'Port is in use without a matching process record. Refusing to stop an unknown process.'
} else { Write-Output 'No recorded backend process.' }
if ($Database) { Invoke-DayuCompose @('stop','mariadb'); Write-Output 'Local database stopped; persistent volume retained.' }
