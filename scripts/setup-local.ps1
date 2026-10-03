# Generates local-only configuration. Does not build, start services, seed data, or call AI.
[CmdletBinding()]
param([string]$InstanceName='dayu-local',[int]$DatabasePort=13306,[int]$BackendPort=18080)
. (Join-Path $PSScriptRoot 'local-common.ps1')
Assert-DayuTools
if ($InstanceName -notmatch '^dayu-[a-z0-9][a-z0-9-]{0,35}$' -or
    $DatabasePort -lt 1024 -or $DatabasePort -gt 65535 -or $BackendPort -lt 1024 -or $BackendPort -gt 65535 -or $DatabasePort -eq $BackendPort) {
    throw 'Invalid instance name or port configuration.'
}
$settingsFile = Join-Path $DayuRepo '.env.local'
if (Test-Path -LiteralPath $settingsFile) {
    $settings = Get-DayuSettings
    if (($PSBoundParameters.ContainsKey('InstanceName') -and $settings.Instance -ne $InstanceName) -or
        ($PSBoundParameters.ContainsKey('DatabasePort') -and $settings.DbPort -ne $DatabasePort) -or
        ($PSBoundParameters.ContainsKey('BackendPort') -and $settings.HttpPort -ne $BackendPort)) {
        throw 'Existing local configuration differs. Setup will not silently switch databases or ports.'
    }
} else {
    Write-DayuNewFile $settingsFile "# Local-only startup settings. Empty data paths mean not configured.`nDAYU_LOCAL_INSTANCE=$InstanceName`nDAYU_LOCAL_DB_PORT=$DatabasePort`nDAYU_LOCAL_HTTP_PORT=$BackendPort`nDAYU_NETCDF_ROOT=`nDAYU_WEBP_ROOT=`nDAYU_COLORBAR_ROOT=`n"
    $settings = Get-DayuSettings
}
$dbFile = Join-Path $DayuRepo '.env.local-db'
if (!(Test-Path -LiteralPath $dbFile)) {
    $volumes = @(& docker volume ls --format '{{.Name}}')
    if ($LASTEXITCODE -ne 0) { throw 'Cannot check existing database volumes.' }
    if ($volumes -contains $settings.Volume) { throw 'Database volume already exists but its password file is missing. Restore credentials; do not reset the volume.' }
    Write-DayuNewFile $dbFile ("# Local database password; never commit.`nDAYU_LOCAL_DB_PASSWORD="+(New-DayuSecret)+"`n")
}
$adminFile = Join-Path $DayuRepo '.env.local-admin'
if (!(Test-Path -LiteralPath $adminFile)) {
    # Lost administrator credentials must not be silently replaced for an existing database.
    $volumes = @(& docker volume ls --format '{{.Name}}')
    if ($LASTEXITCODE -ne 0) { throw 'Cannot check existing database volumes.' }
    if ($volumes -contains $settings.Volume) { throw 'Existing database has no local administrator credential file. Restore it or explicitly recover the account.' }
    Write-DayuNewFile $adminFile ("# Local application administrator, not a database user.`nDAYU_LOCAL_ADMIN_EMAIL=local-admin@dayu.test`nDAYU_LOCAL_ADMIN_PASSWORD="+(New-DayuSecret)+"`n")
}
foreach ($check in @(@{File=$dbFile;Key='DAYU_LOCAL_DB_PASSWORD'},@{File=$adminFile;Key='DAYU_LOCAL_ADMIN_PASSWORD'})) {
    $data = Read-DayuEnv $check.File
    if ([string]::IsNullOrWhiteSpace($data[$check.Key])) { throw 'A local credential file contains an empty password. Existing files were not overwritten.' }
    $data.Clear()
}
if (!(Test-Path -LiteralPath (Join-Path $DayuRepo '.env'))) {
    Write-DayuNewFile (Join-Path $DayuRepo '.env') ([IO.File]::ReadAllText((Join-Path $DayuRepo '.env.example')))
}
Write-Output "Local configuration ready: instance=$($settings.Instance), database=127.0.0.1:$($settings.DbPort), backend=$($settings.BaseUrl)"
Write-Output 'Existing configuration/secrets preserved. No services started. Next: scripts/build-local.ps1, then scripts/start-local.ps1.'
