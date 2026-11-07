# MyAgent memory engine v1 build script (scheduled task entry)
param(
    [switch]$Tests = $false
)
$ErrorActionPreference = 'Continue'
$proj = 'A:\Dev\MyAgent'
$log = Join-Path $proj 'build-mem.log'
Set-Location $proj
"=== build start $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') tests=$Tests ===" | Out-File $log -Encoding utf8

$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.12'
$env:ANDROID_HOME = 'C:\Users\guest\AppData\Local\Android\Sdk'

# ── preflight 闸门（2026-11-07 加）: 环境不对就不构建 ──
& (Join-Path $proj 'preflight.ps1')
if ($LASTEXITCODE -ne 0) {
    '=== preflight FAILED, build aborted ===' | Out-File $log -Append -Encoding utf8
    exit 1
}

if ($Tests) {
    & .\gradlew.bat ':app:testPureUnitTest' --tests 'me.rerere.rikkahub.data.memory.*' --no-daemon --console=plain -x :web:installWebUiDeps -x :web:buildWebUi *>> $log
} else {
    & .\gradlew.bat assemblePure --no-daemon --console=plain -x :web:installWebUiDeps -x :web:buildWebUi *>> $log
}
$code = $LASTEXITCODE
"=== exit code: $code ===" | Out-File $log -Append -Encoding utf8
"=== end $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') ===" | Out-File $log -Append -Encoding utf8
exit $code

