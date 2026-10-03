# Whitelist only the runtime settings this launcher owns. Never execute .env text or log credentials.
function Get-DayuRuntime([switch]$DisableCopilot) {
    $local = Read-DayuEnv (Join-Path $DayuRepo '.env.local')
    $roots = @{}
    $catalog = @{}
    foreach ($mapping in @(@('DAYU_NETCDF_ROOT','netcdf-data'),@('DAYU_WEBP_ROOT','webp-preview'),@('DAYU_COLORBAR_ROOT','colorbar'))) {
        $value = $local[$mapping[0]]
        if ([string]::IsNullOrWhiteSpace($value)) { continue }
        if (![IO.Path]::IsPathRooted($value) -or ![IO.Directory]::Exists($value)) {
            throw "$($mapping[0]) must name an existing absolute directory. No services were started."
        }
        $full = [IO.Path]::GetFullPath($value)
        if ($mapping[1] -eq 'colorbar') { $catalog['colorbar-root']=$full } else { $roots[$mapping[1]]=$full }
    }
    $aiFile=Join-Path $DayuRepo '.env'
    $ai=if (Test-Path -LiteralPath $aiFile) { Read-DayuEnv $aiFile } else { @{} }
    $enabled=$false
    if ($ai['DAYU_COPILOT_ENABLED'] -and ![bool]::TryParse($ai['DAYU_COPILOT_ENABLED'],[ref]$enabled)) {
        throw 'DAYU_COPILOT_ENABLED must be true or false.'
    }
    if ($DisableCopilot) { $enabled=$false }
    $key=if ($enabled) { $ai['DASHSCOPE_API_KEY'] } else { $null }
    if ($enabled -and [string]::IsNullOrWhiteSpace($key)) { throw 'Copilot is enabled but DASHSCOPE_API_KEY is empty. No services were started.' }
    $model=if ($ai['DAYU_COPILOT_QWEN_MODEL']) { $ai['DAYU_COPILOT_QWEN_MODEL'] } else { 'qwen3.8-flash' }
    $base=if ($ai['DAYU_COPILOT_QWEN_BASE_URL']) { $ai['DAYU_COPILOT_QWEN_BASE_URL'] } else { 'https://dashscope.aliyuncs.com/compatible-mode/v1' }
    $endpoint=$null
    if (![uri]::TryCreate($base,[UriKind]::Absolute,[ref]$endpoint) -or $endpoint.Scheme -ne 'https' -or
        $endpoint.UserInfo -or $endpoint.Query -or $endpoint.Fragment -or
        $endpoint.Host -notmatch '(^|\.)aliyuncs\.com$') {
        throw 'Qwen base URL must be an HTTPS Alibaba Cloud endpoint without embedded credentials.'
    }
    $properties=@{dayu=@{indexing=@{roots=$roots};catalog=$catalog;media=@{enabled=$true};
        copilot=@{enabled=$enabled;provider='qwen';qwen=@{model=$model;'base-url'=$base}}}}
    $json=$properties | ConvertTo-Json -Depth 8 -Compress
    $ai.Clear()
    # The key is separate from the JSON and command line. Callers must not print this object.
    return @{Json=$json;ApiKey=$key;CopilotEnabled=$enabled}
}
