$ErrorActionPreference = 'Stop'
$bt = 'C:\Users\guest\AppData\Local\Android\Sdk\build-tools\37.0.0'
$src = 'A:\Dev\MyAgent\app\build\outputs\apk\pure\app-arm64-v8a-pure-unsigned.apk'
$align = 'C:\Users\guest\AppData\Local\Temp\myagent-mem-aligned.apk'
$out = 'C:\Users\guest\AppData\Local\Temp\myagent-mem-signed.apk'
Set-Location $bt
& .\zipalign.exe -f -p 4 $src $align
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }
& .\apksigner.bat sign --ks C:\Users\guest\AppData\Local\Temp\signing\myagent.jks --ks-pass pass:REDACTED --key-pass pass:REDACTED --ks-key-alias myagent --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true --out $out $align
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }
& .\apksigner.bat verify --print-certs $out | Select-Object -First 4
Write-Output ("size=" + (Get-Item $out).Length)
