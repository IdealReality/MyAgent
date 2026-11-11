---
name: build-and-sign
description: How to build, sign and install this app (RikkaHub fork). Use when you need to compile Kotlin, produce an APK, or get an APK onto the device.
---

# Build, sign, install

## 1. Build
Run from `A:\Dev\MyAgent`:

- Full APK: `.\runbuild.ps1` (calls `gradlew assemblePure`)
- Kotlin only: `.\buildk.ps1` (calls `gradlew :app:compilePureKotlin`, ~17s, logs to `verify-compile.log`)
- Unit tests: `.\runbuild.ps1 -Tests` (calls `gradlew :app:testPureUnitTest --tests me.rerere.rikkahub.data.memory.*`)

**JAVA_HOME must be `C:\Program Files\Java\jdk-17.0.12`.**
The system default `java` is 26.0.1 and the build FAILS with it. `runbuild.ps1` sets this itself; if you call gradlew directly you must set it first.

Also required: `ANDROID_HOME=C:\Users\guest\AppData\Local\Android\Sdk`

Common flags: `--no-daemon --console=plain -x :web:installWebUiDeps -x :web:buildWebUi`

Task names that actually exist (verified):
- `assemblePure`
- `:app:compilePureKotlin`  <- NOT compilePureDebugKotlin, `pure` is a buildType
- `:app:testPureUnitTest`   <- NOT testPureDebugUnitTest

## 2. Sign  <- this is the step people miss
`assemblePure` produces **UNSIGNED** APKs at
`app\build\outputs\apk\pure\app-arm64-v8a-pure-unsigned.apk`

They cannot be installed as-is. Run:

    .\sign-pure.ps1

It does zipalign + apksigner (v2/v3) using the release keystore, and writes:
`%TEMP%\myagent-mem-signed.apk`

Keystore: `%TEMP%\signing\myagent.jks` (alias `myagent`).
Credentials are read at run time from `local.properties` (storeFile/storePassword/keyAlias/keyPassword); nothing is hardcoded in `sign-pure.ps1`.

Verify: the script prints the V3 signer cert, `CN=My Agent`.
applicationId of the pure variant is `com.myagent.app.debug` (base `com.myagent.app` + `.debug` suffix).

## 3. Install
SFTP paths on this host need a leading slash: `/C:/Users/guest/...` (plain `C:/...` fails).

    cp /sdcard/Download/x.apk /data/local/tmp/x.apk
    pm install -r /data/local/tmp/x.apk

Copying straight from `/sdcard` fails: system_server cannot read fuse (`avc: denied ... tclass=file`). Always stage in `/data/local/tmp/`.

## Pitfalls
- **Never run build and sign in parallel.** The signer will pick up the previous unsigned APK. Always wait for the build to return first.
- Do not use `background=true` against this host: the tool wraps commands with Unix `>/dev/null 2>&1` and PowerShell rejects it.
- Large downloads from GitHub need the proxy: `curl.exe -x http://127.0.0.1:7897 ...`