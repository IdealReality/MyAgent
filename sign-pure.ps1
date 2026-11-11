$ErrorActionPreference = 'Stop'
# All paths and credentials come from the environment / local.properties.
# Nothing here may be machine-specific: this file is published.
$proj = $PSScriptRoot
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$bt = Join-Path $sdk 'build-tools\37.0.0'
$src = Join-Path $proj 'app\build\outputs\apk\pure\app-arm64-v8a-pure-unsigned.apk'
$tmp = [System.IO.Path]::GetTempPath()
$align = Join-Path $tmp 'myagent-mem-aligned.apk'
$out = Join-Path $tmp 'myagent-mem-signed.apk'

$lpLines = Get-Content (Join-Path $proj 'local.properties')
function Get-Prop([string]$key) {
    $l = $lpLines | Where-Object { $_ -match "^$key=" } | Select-Object -First 1
    if (-not $l) { return $null }
    return ($l -replace "^$key=", '' -replace '\\\\', '\' -replace '\\:', ':')
}

$ksPath = Get-Prop 'storeFile'
$ksPass = Get-Prop 'storePassword'
$alias = Get-Prop 'keyAlias'
$keyPass = Get-Prop 'keyPassword'
if (-not $keyPass) { $keyPass = $ksPass }
if (-not $ksPath -or -not $ksPass -or -not $alias) {
    throw 'local.properties is missing storeFile/storePassword/keyAlias; cannot sign.'
}

Set-Location $bt
& .\zipalign.exe -f -p 4 $src $align
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }
& .\apksigner.bat sign --ks $ksPath --ks-pass "pass:$ksPass" --key-pass "pass:$keyPass" --ks-key-alias $alias --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true --out $out $align
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }
& .\apksigner.bat verify --print-certs $out | Select-Object -First 4
Write-Output ("size=" + (Get-Item $out).Length)
