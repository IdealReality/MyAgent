# preflight.ps1 — MyAgent 构建前置检查
# 失败返回 1，调用方必须 `if ($LASTEXITCODE -ne 0) { exit 1 }`
# 目的：把 HOT.md 里可自动验证的坑变成硬性闸门。

$ErrorActionPreference = 'Continue'
$fails = @()
$warns = @()

# ── 1. JDK 17 ──
$jdk = 'C:\Program Files\Java\jdk-17.0.12'
if (-not (Test-Path (Join-Path $jdk 'bin\java.exe'))) {
    $fails += "JDK17 不存在: $jdk"
} else {
    $v = (& (Join-Path $jdk 'bin\java.exe') -version 2>&1 | Select-Object -First 1)
    if ($v -notmatch '17\.') { $fails += "JDK 版本不是 17: $v" }
}

# ── 2. local.properties 与签名文件 ──
$lp = Join-Path $PSScriptRoot 'local.properties'
$ksPath = $null
if (-not (Test-Path $lp)) {
    $fails += "local.properties 缺失: $lp"
} else {
    $line = (Select-String -Path $lp -Pattern '^storeFile=' -ErrorAction SilentlyContinue |
             Select-Object -First 1).Line
    if ($line) {
        # Properties 转义: C\:\\Users\\X  ->  C:\Users\X
        $ksPath = $line -replace '^storeFile=', '' -replace '\\\\', '\' -replace '\\:', ':'
        if (-not (Test-Path $ksPath)) {
            $fails += "storeFile 指向的 keystore 不存在: $ksPath"
        }
    } else {
        $warns += 'local.properties 没有 storeFile（debug 变体会用 ~/.android/debug.keystore，可能与已装包不匹配）'
    }
}

# ── 3. Android SDK ──
# 路径跟着 local.properties 的 sdk.dir 走：机器/账户改名后写死路径会立刻失效。
$sdkLine = (Select-String -Path $lp -Pattern '^sdk\.dir=' -ErrorAction SilentlyContinue |
            Select-Object -First 1).Line
$sdk = if ($sdkLine) {
    $sdkLine -replace '^sdk\.dir=', '' -replace '\\\\', '\' -replace '\\:', ':'
} else {
    "$env:LOCALAPPDATA\Android\Sdk"
}
if (-not (Test-Path $sdk)) { $fails += "Android SDK 不存在: $sdk" }

# ── 4. 磁盘空间 ──
$drive = (Split-Path $PSScriptRoot -Qualifier).TrimEnd(':')
$free = [math]::Round((Get-PSDrive $drive).Free / 1GB, 1)
if ($free -lt 5) { $fails += "$drive 盘剩余 ${free}GB，构建需要至少 5GB" }

# ── 5. git 状态（提示，不阻断）──
Push-Location $PSScriptRoot
$branch = (git branch --show-current 2>$null)
$dirty = (git status --short 2>$null | Measure-Object).Count
Pop-Location
if ($dirty -gt 0) { $warns += "工作区有 $dirty 个未提交改动（分支 $branch）" }

# ── 输出 ──
Write-Host ''
Write-Host '=== preflight ===' -ForegroundColor Cyan
Write-Host "  JAVA_HOME : $jdk"
Write-Host ("  keystore  : " + $(if ($ksPath) { $ksPath } else { '(未配置)' }))
Write-Host "  分支      : $branch / 未提交 $dirty"
Write-Host "  A 盘剩余  : ${free}GB"
Write-Host ''
Write-Host '--- 高频坑提醒 ---' -ForegroundColor Yellow
Write-Host '  * APK 覆盖安装签名必须与已装包一致；debug 变体默认用 debug.keystore，很可能不匹配'
Write-Host '  * 手机装包先 cp 到 /data/local/tmp（system_server 读不了 /sdcard）'
Write-Host '  * SFTP 路径要前导斜杠：/A:/Dev/...  不是 A:/Dev/...'
Write-Host '  * 禁止 background=true（Unix 重定向包装，Windows 必失败）'
Write-Host ''

if ($warns.Count) { $warns | ForEach-Object { Write-Host "WARN  $_" -ForegroundColor Yellow } }
if ($fails.Count) {
    $fails | ForEach-Object { Write-Host "FAIL  $_" -ForegroundColor Red }
    Write-Host ''
    Write-Host 'preflight FAILED — 不要继续构建' -ForegroundColor Red
    exit 1
}
Write-Host 'preflight OK' -ForegroundColor Green
exit 0
