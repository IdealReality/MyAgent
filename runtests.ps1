# MyAgent memory engine v1 unit-test script
# Fix 1: task name is testPureUnitTest (pure is a buildType, no flavor dimension)
# Fix 2: machine PATH contains a stray double-quote (Tailscale entry), which breaks
#        the Gradle Test Executor command line (-Djava.library.path swallows the
#        classpath and the JVM tries to load main class "Files\Tailscale\;C:\Program").
#        Sanitize PATH before forking gradle.
$ErrorActionPreference = 'Continue'
$proj = 'A:\Dev\MyAgent'
$log = Join-Path $proj 'build-mem-test.log'
Set-Location $proj
"=== test start $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') ===" | Out-File $log -Encoding utf8
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.12'
$env:ANDROID_HOME = 'C:\Users\guest\AppData\Local\Android\Sdk'
$env:Path = ($env:Path -replace '"', '')
& .\gradlew.bat ':app:testPureUnitTest' '--tests' 'me.rerere.rikkahub.data.memory.*' --no-daemon --console=plain -x :web:installWebUiDeps -x :web:buildWebUi *>> $log
$code = $LASTEXITCODE
"=== exit code: $code ===" | Out-File $log -Append -Encoding utf8
exit $code
