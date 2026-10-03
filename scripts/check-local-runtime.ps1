# Narrow configuration checks only; synthetic files, no Docker/database/network/model requests.
[CmdletBinding()]
param()
. (Join-Path $PSScriptRoot 'local-common.ps1')
. (Join-Path $PSScriptRoot 'local-runtime.ps1')
$originalRepo=$DayuRepo
$fixture=Join-Path ([IO.Path]::GetTempPath()) ('dayu-runtime-check-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture | Out-Null
$caseNumber=0
function Set-Case([string]$Local,[string]$Ai) {
    $script:caseNumber++
    $script:DayuRepo=Join-Path $fixture ([string]$script:caseNumber)
    New-Item -ItemType Directory -Path $script:DayuRepo | Out-Null
    Write-DayuNewFile (Join-Path $script:DayuRepo '.env.local') $Local
    if ($Ai) { Write-DayuNewFile (Join-Path $script:DayuRepo '.env') $Ai }
}
function Assert-That([bool]$Condition,[string]$Message) { if (!$Condition) { throw $Message } }
function Assert-Rejected([scriptblock]$Action) {
    $rejected=$false
    try { & $Action | Out-Null } catch { $rejected=$true }
    Assert-That $rejected 'Expected invalid configuration to be rejected.'
}
try {
    Set-Case '# empty local configuration' ''
    $runtime=Get-DayuRuntime
    Assert-That (!$runtime.CopilotEnabled -and !$runtime.ApiKey) 'Missing AI configuration must disable calls.'
    foreach($directory in @('nc files','webp files','public files')) { New-Item -ItemType Directory -Path (Join-Path $fixture $directory) | Out-Null }
    $paths="DAYU_NETCDF_ROOT=$(Join-Path $fixture 'nc files')`nDAYU_WEBP_ROOT=$(Join-Path $fixture 'webp files')`nDAYU_COLORBAR_ROOT=$(Join-Path $fixture 'public files')`n"
    Set-Case $paths "DAYU_COPILOT_ENABLED=true`nDASHSCOPE_API_KEY=configuration-test-only`nDAYU_COPILOT_QWEN_MODEL=qwen3.8-flash`n"
    $runtime=Get-DayuRuntime
    $json=$runtime.Json | ConvertFrom-Json
    Assert-That ($runtime.CopilotEnabled -and $json.dayu.copilot.qwen.model -eq 'qwen3.8-flash') 'Configured model or enabled flag not applied.'
    Assert-That ($json.dayu.indexing.roots.'netcdf-data' -eq (Join-Path $fixture 'nc files')) 'NC logical storage key changed.'
    Assert-That ($json.dayu.indexing.roots.'webp-preview' -eq (Join-Path $fixture 'webp files')) 'WebP logical storage key changed.'
    Assert-That (!$runtime.Json.Contains($runtime.ApiKey)) 'Key must not be serialized into config JSON.'
    $runtime=Get-DayuRuntime -DisableCopilot
    Assert-That (!$runtime.CopilotEnabled -and !$runtime.ApiKey) 'Forced disable must not load a key.'
    Set-Case '' 'DAYU_COPILOT_ENABLED=true'
    Assert-Rejected { Get-DayuRuntime }
    Set-Case 'DAYU_NETCDF_ROOT=relative-path' ''
    Assert-Rejected { Get-DayuRuntime }
    Set-Case '' 'DAYU_COPILOT_ENABLED=maybe'
    Assert-Rejected { Get-DayuRuntime }
    Set-Case '' "DAYU_COPILOT_ENABLED=true`nDASHSCOPE_API_KEY=configuration-test-only`nDAYU_COPILOT_QWEN_BASE_URL=https://example.test/v1`n"
    Assert-Rejected { Get-DayuRuntime }
    Write-Output 'Runtime checks passed: defaults, directory mapping, model/key isolation, forced disable, missing key, relative path, invalid flag, untrusted endpoint.'
} finally { $script:DayuRepo=$originalRepo; $runtime=$null }
