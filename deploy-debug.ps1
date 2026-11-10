# deploy-debug.ps1 — 把构建好的包送到手机：安装 → 恢复无障碍 → 启动 → 查崩溃
#
# 为什么单独一个脚本：装包会把这个包的无障碍服务从 enabled_accessibility_services 里
# 摘掉（PACKAGE UPDATED 是安全设计，不是省电/崩溃）。之前一次会话里装了 7 次包，
# 就手点了 7 次无障碍。这里把「装完立刻写回去」固化下来，顺便把启动和崩溃检查一起做。
#
# 用法:
#   .\deploy-debug.ps1                 # 先构建+重签，再装
#   .\deploy-debug.ps1 -SkipBuild      # 直接用现成的 C:\Users\guest\myagent-new.apk
#   .\deploy-debug.ps1 -Apk path.apk   # 指定别的包
#
# 前提: python 在 PATH；C:\Users\guest\bin\adb-phone.py 能打通 arch 的 adb server。
param(
    [switch]$SkipBuild = $false,
    [string]$Apk = 'C:\Users\guest\myagent-new.apk'
)

$proj      = 'A:\Dev\MyAgent'
$pkg       = 'com.myagent.app.debug'
$svc       = "$pkg/me.rerere.rikkahub.service.RikkaAccessibilityService"
$adbPhone  = 'C:\Users\guest\bin\adb-phone.py'

# 判定「无障碍真的能用」的唯一可信信号是 Bound services 里出现自己：
# secure 设置里那串名字可以存在而服务仍是 DISABLED，而且重新绑定有 ~10 秒延迟
#（实测 2026-11-07：写完 4 秒查还是没有，再过几秒才出现）。
function Wait-A11yBound([int]$timeoutSec = 20) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $acc = Invoke-Adb @('shell', 'dumpsys accessibility')
        if ($acc.out -match 'Service\[label=My Agent') { return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Invoke-Adb([string[]]$argv) {
    $out = & python $adbPhone @argv 2>&1
    $code = $LASTEXITCODE
    return @{ code = $code; out = (($out | ForEach-Object { "$_" }) -join "`n") }
}

if (-not $SkipBuild) {
    & "$proj\build-debug.ps1"
    if ($LASTEXITCODE -ne 0) { Write-Host '构建/重签失败，中止' -ForegroundColor Red; exit 1 }
}

if (-not (Test-Path $Apk)) { Write-Host "找不到 $Apk" -ForegroundColor Red; exit 1 }
$size = (Get-Item $Apk).Length
Write-Host ("安装 {0}  ({1:N0} 字节)" -f $Apk, $size)

# 必须 push 到 /data/local/tmp 再 `pm install`，**不能用 `adb install`**。
# 实测（Honor / Android 17，2026-11-08，同一份 APK 两次安装对比）：
#   adb install -r            → 系统日志 Performing Streamed Install，装完 flags 里没有
#                               DEBUGGABLE，run-as 直接报 "package not debuggable"
#   adb push + pm install -r  → flags=[ DEBUGGABLE ...] 保留
# 丢了 DEBUGGABLE 就没有 adb run-as（读库、对话 dump）和整条调试通路。
$remote = '/data/local/tmp/myagent-deploy.apk'
$push = Invoke-Adb @('push', $Apk, $remote)
if ($push.code -ne 0) {
    Write-Host "push 到手机失败:`n$($push.out)" -ForegroundColor Red
    exit 1
}
$install = Invoke-Adb @('shell', "pm install -r $remote")
Invoke-Adb @('shell', "rm -f $remote") | Out-Null
if ($install.code -ne 0 -or $install.out -notmatch 'Success') {
    Write-Host "安装失败:`n$($install.out)" -ForegroundColor Red
    exit 1
}

$dbg = Invoke-Adb @('shell', "dumpsys package $pkg | grep -m1 'flags=\['")
if ($dbg.out -notmatch 'DEBUGGABLE') {
    Write-Host '警告: 包没有 DEBUGGABLE 标志 —— run-as/调试会失效（是不是又用了 adb install？）' -ForegroundColor Yellow
}

# 先把进程停掉：pm install 之后旧进程往往还活着，无障碍绑定也还在，
# 这时探 Bound services 会得到「已绑定」的假阳性，等重启进程就没了。
Invoke-Adb @('shell', "am force-stop $pkg") | Out-Null
Start-Sleep -Seconds 1

# ── 无障碍：装包会让系统撤销绑定 ──
# 实测（Android 17 / Honor，2026-11-07）：装包后 `enabled_accessibility_services` 有时
# 还留着我们的名字，但服务在 Bound services 里已经没了 —— 字符串在 ≠ 能用。
# 而且 put 一个**相同的值**不会让 AccessibilityManagerService 重读，必须先把名字摘掉
# 再装回去（实测 2 秒内重新绑定）。
$cur = Invoke-Adb @('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services')
$list = $cur.out.Trim()
if ($list -eq 'null') { $list = '' }

$hasEntry = $list -match [regex]::Escape($svc)
$withEntry = if ($hasEntry) { $list } elseif ($list) { "${list}:${svc}" } else { $svc }
$withoutEntry = (($list -split ':') | Where-Object { $_ -and ($_ -notmatch [regex]::Escape($svc)) }) -join ':'

if (Wait-A11yBound 2) {
    Write-Host '无障碍已绑定，无需改动' -ForegroundColor Green
} else {
    Write-Host '无障碍未绑定，重写设置…'
    if ($hasEntry) {
        Invoke-Adb @('shell', "settings put secure enabled_accessibility_services `"$withoutEntry`"") | Out-Null
        Start-Sleep -Seconds 1
    }
    $put = Invoke-Adb @('shell', "settings put secure enabled_accessibility_services `"$withEntry`"")
    if ($put.code -ne 0) {
        Write-Host "写回无障碍失败（需要 shell 权限）:`n$($put.out)" -ForegroundColor Yellow
    } elseif (Wait-A11yBound 15) {
        Write-Host '无障碍已绑定' -ForegroundColor Green
    } else {
        Write-Host '15 秒内没绑定：去 设置 → 无障碍 → 已安装的应用 里手动开一次 My Agent' -ForegroundColor Yellow
    }
}

# ── 启动 + 崩溃检查 ──
$monkey = Invoke-Adb @('shell', "monkey -p $pkg -c android.intent.category.LAUNCHER 1")
Start-Sleep -Seconds 3

# 用 crash buffer 而不是 grep 全量 logcat：grep 的 pattern 会写进 adbd 自己的日志行，
# 于是「查 FATAL」每次都把自己匹配出来（实测踩过）。
$log = Invoke-Adb @('shell', 'logcat -d -b crash -t 50')
$crash = ($log.out -split "`n") | Where-Object { $_ -and ($_ -notmatch '^\-\-\-') -and ($_ -notmatch 'logcat') }
if ($crash) {
    Write-Host 'crash buffer 里有内容:' -ForegroundColor Red
    $crash | Select-Object -First 10 | ForEach-Object { Write-Host "  $_" }
} else {
    Write-Host 'crash buffer 为空（无 FATAL）' -ForegroundColor Green
}

$upd = Invoke-Adb @('shell', "dumpsys package $pkg | grep -m2 lastUpdateTime")
Write-Host '--- 安装时间（机主在前，分身在后）---'
Write-Host $upd.out

# 真正说明「能不能用」的是 Bound services，不是 secure 设置里那串字符串：
# 实测 Android 17 装包后字符串有时仍留着，服务却是 DISABLED。
if (Wait-A11yBound 10) {
    Write-Host '无障碍已绑定（Bound services 里有 My Agent）' -ForegroundColor Green
} else {
    Write-Host '无障碍未绑定：secure 字符串在也不代表服务活着，去设置里重开一次' -ForegroundColor Yellow
}

if ($monkey.out -match 'Events injected: 1') {
    Write-Host 'app 已拉起' -ForegroundColor Green
    exit 0
}
Write-Host "monkey 没注入事件，看看输出:`n$($monkey.out)" -ForegroundColor Yellow
exit 0
