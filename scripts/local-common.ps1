# Shared helpers for Windows local development. Configuration is data, never evaluated as code.
$ErrorActionPreference = 'Stop'
$DayuRepo = Split-Path -Parent $PSScriptRoot
function Read-DayuEnv([string]$Path) {
    if (!(Test-Path -LiteralPath $Path)) { throw "Missing configuration: $Path. Run scripts/setup-local.ps1 first." }
    $values = @{}
    foreach ($line in [IO.File]::ReadAllLines($Path)) {
        if ($line -match '^\s*([A-Z_]+)=(.*)$') { $values[$matches[1]] = $matches[2].Trim() }
    }
    return $values
}
function Get-DayuSettings {
    $file = Join-Path $DayuRepo '.env.local'
    $values = if (Test-Path -LiteralPath $file) { Read-DayuEnv $file } else { @{} }
    $instance = if ($values['DAYU_LOCAL_INSTANCE']) { $values['DAYU_LOCAL_INSTANCE'] } else { 'dayu-local' }
    if ($instance -notmatch '^dayu-[a-z0-9][a-z0-9-]{0,35}$') { throw 'Invalid local instance name.' }
    $dbPort = if ($values['DAYU_LOCAL_DB_PORT']) { [int]$values['DAYU_LOCAL_DB_PORT'] } else { 13306 }
    $httpPort = if ($values['DAYU_LOCAL_HTTP_PORT']) { [int]$values['DAYU_LOCAL_HTTP_PORT'] } else { 18080 }
    if ($dbPort -lt 1024 -or $dbPort -gt 65535 -or $httpPort -lt 1024 -or $httpPort -gt 65535 -or $dbPort -eq $httpPort) {
        throw 'Local ports must be distinct numbers from 1024 to 65535.'
    }
    $container = if ($instance -eq 'dayu-local') { 'dayu-platform-mariadb-local' } else { "$instance-mariadb" }
    $volume = if ($instance -eq 'dayu-local') { 'dayu-platform-mariadb-local-data' } else { "$instance-mariadb-data" }
    return @{Instance=$instance;DbPort=$dbPort;HttpPort=$httpPort;Container=$container;Volume=$volume;
        BaseUrl="http://127.0.0.1:$httpPort";StateFile=(Join-Path $DayuRepo 'tmp/local-backend.json')}
}
function Get-DayuJdk {
    $java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java -ErrorAction Stop).Source }
    $javac = Join-Path (Split-Path -Parent $java) 'javac.exe'
    if (!(Test-Path -LiteralPath $java) -or !(Test-Path -LiteralPath $javac)) { throw 'JAVA_HOME must point to a full JDK 17 installation, not a JRE.' }
    $compiler = & $javac -version
    if ($LASTEXITCODE -ne 0 -or "$compiler" -notmatch 'javac\s+(\d+)' -or [int]$matches[1] -lt 17) {
        throw 'JDK 17 or later is required. Check JAVA_HOME (JDK 17 is the verified version).'
    }
    return @{Java=$java;Home=(Split-Path -Parent (Split-Path -Parent $java))}
}
function Assert-DayuTools {
    foreach ($tool in @('docker')) {
        if (!(Get-Command $tool -ErrorAction SilentlyContinue)) { throw "Install $tool and reopen the terminal." }
    }
    Get-DayuJdk | Out-Null
    $composeVersion = & docker compose version --short
    if ($LASTEXITCODE -ne 0 -or "$composeVersion" -notmatch '^v?(\d+)\.(\d+)' -or
        ([int]$matches[1] -lt 2 -or ([int]$matches[1] -eq 2 -and [int]$matches[2] -lt 22))) {
        throw 'Docker Compose 2.22 or later is required.'
    }
    & docker info --format '{{.ServerVersion}}' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Start Docker Desktop and wait until its engine is ready.' }
}
function Invoke-DayuCompose([string[]]$Commands) {
    $settings = Get-DayuSettings
    $values = @{
        DAYU_LOCAL_DB_PORT=[string]$settings.DbPort
        DAYU_LOCAL_DB_CONTAINER=$settings.Container
        DAYU_LOCAL_DB_VOLUME=$settings.Volume
        DAYU_LOCAL_DB_PASSWORD=(Read-DayuEnv (Join-Path $DayuRepo '.env.local-db'))['DAYU_LOCAL_DB_PASSWORD']
    }
    $previous = @{}
    try {
        foreach ($name in $values.Keys) {
            $previous[$name] = [Environment]::GetEnvironmentVariable($name,'Process')
            [Environment]::SetEnvironmentVariable($name,$values[$name],'Process')
        }
        & docker compose --project-name $settings.Instance --env-file (Join-Path $DayuRepo '.env.local-db') `
            -f (Join-Path $DayuRepo 'infra/compose.local.yml') @Commands
        if ($LASTEXITCODE -ne 0) { throw 'Local Docker Compose command failed. Credentials are not printed.' }
    } finally {
        foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
    }
}
# CreateNew refuses to replace an existing secret, including concurrent setup runs.
function Write-DayuNewFile([string]$Path,[string]$Content) {
    $bytes = [Text.UTF8Encoding]::new($false).GetBytes($Content)
    $stream = [IO.File]::Open($Path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try { $stream.Write($bytes,0,$bytes.Length) } finally { $stream.Dispose() }
}
function New-DayuSecret {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes); return [BitConverter]::ToString($bytes).Replace('-','').ToLowerInvariant() }
    finally { $rng.Dispose() }
}
