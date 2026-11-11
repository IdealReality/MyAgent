# build-debug.ps1 — debug 变体：构建 + 用 myagent.jks 重签 + 备好待传文件
# 固化 2026-11-07 那次手动排查的全部步骤。
param([switch]$SkipSign = $false)

$proj = $PSScriptRoot
Set-Location $proj

# ── 1. 前置检查（失败即中止，不可绕过）──
& "$proj\preflight.ps1"
if ($LASTEXITCODE -ne 0) {
    Write-Host 'preflight 未通过 —— 中止构建' -ForegroundColor Red
    exit 1
}

# ── 2. 构建 ──
# JDK：优先沿用环境里的 JAVA_HOME，其次按常见位置探测（preflight 已校验版本是 17）。
function Resolve-Jdk {
    $cands = @()
    if ($env:JAVA_HOME) { $cands += $env:JAVA_HOME }
    $cands += @(
        'C:\Program Files\Java\jdk-17.0.12',
        'C:\Program Files\Java\jdk-17*',
        'C:\Program Files\Eclipse Adoptium\jdk-17*',
        (Join-Path $env:USERPROFILE '.jdks\jdk-17*')
    )
    foreach ($c in $cands) {
        $hit = @(Get-Item $c -ErrorAction SilentlyContinue |
            Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') }) | Select-Object -First 1
        if ($hit) { return $hit.FullName }
    }
    return $null
}
$env:JAVA_HOME = Resolve-Jdk
if (-not $env:JAVA_HOME) { Write-Host '找不到 JDK，中止构建' -ForegroundColor Red; exit 1 }
# SDK 从 local.properties 的 sdk.dir 推导：写死账户名在改名后必然失效。
$sdkLine = (Select-String -Path (Join-Path $proj 'local.properties') -Pattern '^sdk\.dir=' -ErrorAction SilentlyContinue |
            Select-Object -First 1).Line
$env:ANDROID_HOME = if ($sdkLine) {
    $sdkLine -replace '^sdk\.dir=', '' -replace '\\\\', '\' -replace '\\:', ':'
} else {
    "$env:LOCALAPPDATA\Android\Sdk"
}
$log = Join-Path $proj 'build-debug.log'
"=== $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') assembleDebug ===" | Out-File $log -Encoding utf8

& .\gradlew.bat assembleDebug --no-daemon --console=plain -x :web:installWebUiDeps -x :web:buildWebUi *>> $log
if ($LASTEXITCODE -ne 0) {
    Write-Host "构建失败，日志: $log" -ForegroundColor Red
    Get-Content $log -Tail 20
    exit 1
}

# ── 3. 定位产物（时间戳 + 大小，不信 gradle 的 stdout）──
$apk = Join-Path $proj 'app\build\outputs\apk\debug\app-arm64-v8a-debug.apk'
if (-not (Test-Path $apk)) {
    Write-Host '找不到 arm64-v8a debug 产物' -ForegroundColor Red
    exit 1
}
$f = Get-Item $apk
Write-Host ("产物: {0}  {1} MB  {2}" -f $f.Name, [math]::Round($f.Length/1MB,1), $f.LastWriteTime)

if ($SkipSign) { Write-Host '已跳过重签'; exit 0 }

# ── 4. 重签 ──
# debug 变体用的是 ~/.android/debug.keystore，与手机已装包不匹配，
# 必须用 local.properties 里的 storeFile 重签。
$lpLines = Get-Content (Join-Path $proj 'local.properties')

function Get-Prop([string]$key) {
    $l = $lpLines | Where-Object { $_ -match "^$key=" } | Select-Object -First 1
    if (-not $l) { return $null }
    return ($l -replace "^$key=", '' -replace '\\\\', '\' -replace '\\:', ':')
}

$ksPath = Get-Prop 'storeFile'
$ksPass = Get-Prop 'storePassword'
$alias  = Get-Prop 'keyAlias'
$keyPass = Get-Prop 'keyPassword'
if (-not $keyPass) { $keyPass = $ksPass }

if (-not $ksPath -or -not $ksPass -or -not $alias) {
    Write-Host 'local.properties 缺少 storeFile/storePassword/keyAlias，无法重签' -ForegroundColor Red
    exit 1
}

$signer = Join-Path $env:ANDROID_HOME 'build-tools\37.0.0\apksigner.bat'
$signed = Join-Path $proj 'app-debug-signed.apk'
Remove-Item $signed -ErrorAction SilentlyContinue

& $signer sign --ks $ksPath --ks-key-alias $alias `
    --ks-pass "pass:$ksPass" --key-pass "pass:$keyPass" `
    --out $signed $apk
if ($LASTEXITCODE -ne 0) { Write-Host '重签失败' -ForegroundColor Red; exit 1 }

Write-Host '--- 签名校验 ---'
& $signer verify --print-certs $signed | Select-String 'SHA-256'

# ── 5. 备好待传文件（SFTP 目标）──
$staged = Join-Path $env:USERPROFILE 'myagent-new.apk'
Copy-Item $signed $staged -Force
$g = Get-Item $staged
Write-Host ''
Write-Host ("待安装: {0}  {1} MB" -f $staged, [math]::Round($g.Length/1MB,1)) -ForegroundColor Green
Write-Host ("SFTP staged: {0}" -f $staged)
Write-Host '手机装包: cp 到 /data/local/tmp 后 pm install -r'
exit 0
